"""Workshop audio end to end: the queue wiring, the transcript coming back, and the two aids.

These need Postgres — the behaviour under test is a row appearing in ``MediaProcessingJob`` and a
column changing on ``MediaFile``, neither of which can be asserted in Python — so the module skips
itself when ``DATABASE_URL`` does not point at a local database, exactly as ``test_stage_sync``
does.

    docker compose up -d postgres minio
    cd backend && .venv/Scripts/python.exe -m prisma migrate deploy --schema prisma/schema.prisma

Nothing here reaches a provider. The transcription queue is asserted at the point where the job is
CREATED, and the two synchronous endpoints are asserted on the paths that answer before any
provider is contacted: a feature that is switched off, a clip that is too big to accept, and the
id-less dictation address that no longer accepts a recording at all.
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
        # A clip STRANDED by a job that died before the provider answered. ``transcriptStatus`` was
        # written QUEUED by the enqueue (``media_queue`` :305) and never written again, because the
        # only writer past that point is ``_apply_transcription_result`` and the job never reached
        # it: ``download_to_temp`` raised on an object key that no longer resolves, and
        # ``_handle_job_failure`` updates the JOB row and nothing else. At exhausted attempts the
        # job is FAILED and the column still says a transcript is on its way.
        #
        # BUILT HERE RATHER THAN BY DRAINING THE QUEUE, and that is not laziness: reproducing it
        # through the worker needs three failed attempts spread across the retry ladder's 1-then-2
        # minute ``runAfter`` backoff, and a TRANSCRIPTION job is only drained inside the off-peak
        # window or on an idle server. The pairing itself is not contrived — it is exactly what
        # ``_handle_job_failure`` leaves behind on every pre-answer exhaustion.
        stranded = await db.mediafile.create(data={
            "originalFilename": "warp-setting-interview.m4a",
            "mediaType": "AUDIO",
            "mimeType": "audio/mp4",
            "sizeBytes": 512_000,
            "bucket": "test-bucket",
            "objectKey": f"media/{user.id}/{stamp}-stranded.m4a",
            "uploadedById": user.id,
            "transcriptStatus": "QUEUED",
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
            "user": user.id,
        }


@pytest.fixture
def client(env):
    return env["client"]


@pytest.fixture
def workshop(client):
    """A workshop nobody has asked the consent question about. ``dictationConsent`` is NOT_RECORDED.

    LEFT UNCONSENTED DELIBERATELY, so that the tests which need a grant have to ask for one. Making
    this fixture consented would have been the smaller diff and would have deleted the only evidence
    that the gate exists: every queue test would pass, and so would a build with the gate removed.
    """
    response = client.post("/api/design-workshops", json={"title": "Audio test workshop"})
    assert response.status_code == 201, response.text
    return response.json()["id"]


def _grant(client, workshop_id):
    """Record the artisan's agreement for this workshop, as the designer sitting with them would."""
    response = client.post(
        f"/api/design-workshops/{workshop_id}/dictation-consent", json={"decision": "GRANTED"}
    )
    assert response.status_code == 200, response.text
    return workshop_id


def _withdraw(client, workshop_id):
    """The artisan changes their mind. REFUSED is how a consent is taken back — there is no un-record."""
    response = client.post(
        f"/api/design-workshops/{workshop_id}/dictation-consent", json={"decision": "REFUSED"}
    )
    assert response.status_code == 200, response.text
    return workshop_id


@pytest.fixture
def granted(client, workshop):
    """A workshop whose artisan has agreed to their recordings being written down elsewhere."""
    return _grant(client, workshop)


def _save_audio(client, workshop_id, media_id):
    return client.put(
        f"/api/design-workshops/{workshop_id}/stages/{STAGE}",
        json={"entries": [{"entityKey": ENTITY, "data": {"artisanAudio": media_id}}]},
    )


# --------------------------------------------------------------------------------------
# 1. An AUDIO field on a stage is transcribed like any other recording
# --------------------------------------------------------------------------------------


async def test_audio_attached_to_a_stage_is_queued_for_transcription(env, granted):
    """The gap this closes: the clip uploaded, the id landed on the stage, and nothing happened.

    The assertion is deliberately about the QUEUE rather than about a transcript — a workshop clip
    must enter the same job table as an interview recording, so that everything downstream (the
    off-peak window, the provider ranking, the rate-limit backoff, the admin "Transcribe now")
    applies to it without a line of new code.

    ``granted`` AND NOT ``workshop``: this test used to run against a workshop nobody had asked, and
    passing was the evidence that consent governed the dictation and not the recording. The mechanics
    it is really about — one job, in the shared table — are unchanged once the artisan has agreed.
    """
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


async def test_saving_the_same_stage_again_does_not_queue_a_second_job(env, granted):
    """A designer correcting a typo, or a phone replaying its sync queue, must not pay for the
    same audio twice — nor let two jobs race to write one transcript column."""
    client = env["client"]
    assert _save_audio(client, granted, env["fresh"]).json()["transcriptionsQueued"] in (0, 1)
    assert _save_audio(client, granted, env["fresh"]).json()["transcriptionsQueued"] == 0


async def test_an_existing_transcript_is_never_re_transcribed(env, granted):
    """Re-running transcription over a clip a researcher has already corrected by hand would
    overwrite their correction with the provider's guess."""
    client = env["client"]
    assert _save_audio(client, granted, env["done"]).json()["transcriptionsQueued"] == 0


async def test_a_clip_whose_job_died_before_the_provider_answered_is_re_queued(env, granted):
    """THE STRAND, AND THE ONLY RECOVERY A DESIGNER CAN REACH.

    ``MediaFile.transcriptStatus`` is written QUEUED at enqueue time and is written again only by
    ``_apply_transcription_result``, which runs only once a provider has ANSWERED. Every exception
    before that — an object key that no longer resolves, object storage unreachable overnight, the
    provider call itself — goes to ``_handle_job_failure``, which touches the JOB and not the clip.
    So an exhausted job leaves FAILED beside a column that still reads QUEUED.

    ``QUEUED`` used to sit in ``workshop_transcripts._SETTLED_TRANSCRIPT_STATUSES``, so that clip was
    skipped by every later save for ever; ``/media/complete`` would not rescue it either
    (``_finish_pending_media`` re-enqueues only when ``processingJobs`` is EMPTY, and a FAILED job is
    still a job); and ``report_annexures.annexure_warnings`` classified it as "still being
    transcribed", so the one message the designer got told them to keep waiting. The only way out was
    an admin finding the job in the queue panel and pressing Retry.

    The fix reads QUEUED/PROCESSING as a CLAIM about the queue rather than as a fact, and checks the
    queue — which this function was already reading anyway. Saving the stage is now the recovery.
    """
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
    """THE OTHER HALF, AND IT IS WHY THE FIX IS A QUEUE READ AND NOT A SHORTER STATUS LIST.

    Deleting QUEUED from the settled set would pass the test above and re-queue every clip the worker
    has not got to yet — a second job racing the first to write one transcript column, and a second
    provider bill for the same audio. Here the upload itself queued the clip, so the column's QUEUED
    is backed by a real job and the save must queue nothing.
    """
    client = env["client"]
    media = _upload_audio(
        client, env["user"], tag=DW_TAG, tag_id=granted, requests=["TRANSCRIPTION"]
    )
    assert media["transcriptStatus"] == "QUEUED"
    assert any(j["status"] == "QUEUED" for j in _jobs(media)), "the premise: a live job"
    assert _save_audio(client, granted, media["id"]).json()["transcriptionsQueued"] == 0


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


@pytest.fixture
def consented(client, workshop):
    """A workshop whose artisan has agreed to their recordings being sent for transcription.

    Required now that the only route which transcribes anything is the gated one: without the consent
    these two would be refused with a 409 before the size and the empty checks were ever reached, and
    both tests would pass for the wrong reason.
    """
    response = client.post(
        f"/api/design-workshops/{workshop}/dictation-consent", json={"decision": "GRANTED"}
    )
    assert response.status_code == 200, response.text
    return workshop


async def test_the_dictation_size_cap_is_enforced(client, consented):
    """The cap is what stops a synchronous endpoint from becoming a back door into the
    transcription queue — a long interview posted here would hold a worker for the whole provider
    round trip, with no retry and no rate-limit backoff. The message says where to put it
    instead."""
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
    """The same 410 the DB-free suite asserts, on the mounted application rather than on the router.

    Worth the duplication for one reason: this module drives ``app.main.app``, so it is the only place
    that would notice a middleware, a prefix or a route added elsewhere putting something back in front
    of this address. A retired door that a later change quietly re-opens is the shape of defect this
    lane exists to close.
    """
    response = client.post(
        "/api/design-workshops/dictate",
        files={"file": ("dictation.webm", b"\x00" * 32, "audio/webm")},
    )
    assert response.status_code == 410, response.text
    assert "{workshop_id}/dictate" in response.json()["detail"]


async def test_the_capability_probe_answers_on_the_mounted_application(client):
    """``GET /design-workshops/dictate`` must be answered by its own route and not by
    ``GET /{workshop_id}`` looking up a workshop called "dictate" — which answered 404, which the
    browser reads as "this deployment has no dictation" and renders no microphone for."""
    response = client.get("/api/design-workshops/dictate")
    assert response.status_code == 200, response.text
    assert response.json()["dictatePath"] == "/design-workshops/{workshop_id}/dictate"


# --------------------------------------------------------------------------------------
# 6. THE CONSENT GATE ON STORED RECORDINGS
#
# The half of Tier 3 consent that did not exist. ``DesignWorkshop.dictationConsent`` gated
# ``POST /{id}/dictate`` — thirty seconds of a designer speaking into a field — and did not gate the
# eleven-minute interview with the artisan, which reaches the same ElevenLabs/Deepgram/OpenAI chain
# through ``media_queue.enqueue_media_processing_jobs``.
#
# WHY THESE TESTS ARE HERE AND NOT IN THE DB-FREE FILE. The pure half is asserted there. What is
# asserted here is the WIRING, against a real Postgres and the real mounted application, because the
# defect was never a wrong rule — it was a correct rule with nothing calling it. A unit test of the
# gate would have passed on the broken build. These fail on it.
#
# Every media row is created through ``POST /media/complete`` rather than through ``db``, for the
# reason the ``env`` fixture records about cross-loop use of a shared Prisma client — and because the
# upload route is itself one of the two doors under test.
# --------------------------------------------------------------------------------------

DW_TAG = "designWorkshop"


def _upload_audio(client, user_id, *, tag=None, tag_id=None, requests=None):
    """One AUDIO media row, created the way a client creates one. Returns the response body.

    ``tag``/``tag_id`` are ``linkedRecordType``/``linkedRecordId``: both shipped clients file every
    design-workshop attachment under ``designWorkshop`` with the workshop id, which is the link the
    consent gate reads. ``requests`` is ``processingRequests`` — None means the key is omitted, which
    is the auto-enqueue branch, and a list means the caller asked explicitly, which is what BOTH
    clients actually send for every audio upload.
    """
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


# ---- the upload door -----------------------------------------------------------------


async def test_an_unconsented_workshops_recording_is_stored_but_not_queued(env, workshop):
    """THE DEFECT, ASSERTED AT THE DOOR THE FLEET ACTUALLY DRIVES THROUGH.

    Note ``requests=["TRANSCRIPTION"]``: the reported defect was the auto-enqueue for a body with no
    ``processingRequests``, and a gate placed only on that branch would have closed a door nobody
    uses. Both clients ask EXPLICITLY on every audio upload — the handset's
    ``uploadDesignWorkshopMedia`` sends ``listOf("TRANSCRIPTION")`` and the web's
    ``resolveProcessing`` adds it whenever ``transcribeAudio``, which defaults to true. So this is the
    live path, and it is the one under test.

    THE UPLOAD STILL SUCCEEDS, 201, and that is deliberate: the file is stored and attached and is
    exactly what the designer captured. Failing it would tell a phone at the end of a fortnight's sync
    that its recording was lost, which is false and which would make the app retry for ever.
    """
    media = _upload_audio(
        env["client"], env["user"], tag=DW_TAG, tag_id=workshop, requests=["TRANSCRIPTION"]
    )
    assert _jobs(media) == [], "an artisan nobody asked had their recording queued for a provider"
    assert media["transcriptStatus"] == "FAILED"
    assert "Nobody has recorded yet" in (media["transcriptError"] or "")


async def test_the_auto_enqueue_is_gated_too(env, workshop):
    """The same refusal for a body that names no ``processingRequests`` at all — ``_job_requests``
    adds TRANSCRIPTION for any AUDIO file, and that branch is reachable by any signed-in caller."""
    media = _upload_audio(env["client"], env["user"], tag=DW_TAG, tag_id=workshop)
    assert _jobs(media) == []
    assert media["transcriptStatus"] == "FAILED"


async def test_a_consented_workshops_recording_is_queued_at_upload_time(env, granted):
    """The capability still works, which is half of what a privacy fix has to prove. Same route,
    same body, one recorded answer different."""
    media = _upload_audio(
        env["client"], env["user"], tag=DW_TAG, tag_id=granted, requests=["TRANSCRIPTION"]
    )
    assert len(_jobs(media)) == 1
    assert media["transcriptStatus"] == "QUEUED"
    assert media["transcriptError"] is None


async def test_a_recording_that_is_not_workshop_material_is_still_transcribed(env):
    """RULE 4, AND THE ONE A FAIL-CLOSED CHANGE BREAKS IF IT IS WRITTEN CARELESSLY.

    An audio upload carrying no design-workshop tag is an interview clip, a voice note on a product,
    a file from the miscellaneous-media page. It has been transcribed automatically since long before
    this consent existed, no answer about a design workshop says anything about it, and refusing it
    would stop the repository's oldest working pipeline in the name of a permission nobody was asked
    for. If this test ever fails, the gate has become a switch that turns transcription off.
    """
    media = _upload_audio(env["client"], env["user"], requests=["TRANSCRIPTION"])
    assert len(_jobs(media)) == 1
    assert media["transcriptStatus"] == "QUEUED"


async def test_a_tag_naming_a_workshop_that_does_not_exist_refuses(env):
    """FAIL CLOSED on a workshop the server cannot read. The tag is caller-supplied, so this is the
    shape a typo or a stale offline id arrives in — and "I cannot tell whose workshop this is" must
    not resolve to "send it"."""
    media = _upload_audio(
        env["client"],
        env["user"],
        tag=DW_TAG,
        tag_id="no-such-workshop",
        requests=["TRANSCRIPTION"],
    )
    assert _jobs(media) == []
    assert media["transcriptStatus"] == "FAILED"


# ---- the stage-save door -------------------------------------------------------------


async def test_the_stage_save_refuses_and_says_why_on_the_screen_being_looked_at(env, workshop):
    """THE SHARPEST FORM OF THE DEFECT: this same call reported ``transcriptionsQueued: 1`` on a
    workshop that ``POST /{id}/dictate`` was refusing with a 409 in the same minute.

    ``transcriptionConsentRefusal`` exists because ``transcriptionsQueued: 0`` is ambiguous — it is
    also what a re-save reports — and silence here means the designer waits for a night that never
    comes and reports the feature as broken. It is not broken; nobody has asked the artisan.
    """
    client = env["client"]
    media = _upload_audio(client, env["user"], requests=[])
    response = _save_audio(client, workshop, media["id"])
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["transcriptionsQueued"] == 0
    assert "Nobody has recorded yet" in body["transcriptionConsentRefusal"]

    # And the same workshop refuses a dictation, with the same reason — which is the pair that used
    # to disagree. One door was gated; this asserts they now answer alike.
    refusal = client.post(
        f"/api/design-workshops/{workshop}/dictate",
        files={"file": ("dictation.webm", b"\x00" * 64, "audio/webm")},
    )
    assert refusal.status_code == 409, refusal.text


async def test_a_stage_clip_with_no_tag_is_still_gated_by_the_path_it_arrived_on(env, workshop):
    """The case a tag alone would miss, and it is not hypothetical: a designer can paste any media id
    into a stage field, and a clip uploaded through the generic picker carries no workshop tag.
    ``save_stage`` holds the workshop id in its path, so it hands it down — see
    ``enqueue_stage_transcriptions(design_workshop_id=...)``."""
    client = env["client"]
    media = _upload_audio(client, env["user"], requests=[])
    assert media["linkedRecordType"] is None, "the premise: nothing on the row names the workshop"
    assert _save_audio(client, workshop, media["id"]).json()["transcriptionsQueued"] == 0


async def test_recording_the_answer_later_lets_it_through_on_the_next_save(env, workshop):
    """THE RECOVERY PATH, AND IT IS WHY THE REFUSAL WRITES ``FAILED`` RATHER THAN A TERMINAL STATUS.

    An artisan says no on Tuesday and agrees on Thursday. FAILED is the one status
    ``workshop_transcripts._SETTLED_TRANSCRIPT_STATUSES`` deliberately leaves eligible, so the next
    save of the stage picks the recording up with nothing to clean by hand.
    """
    client = env["client"]
    media = _upload_audio(
        client, env["user"], tag=DW_TAG, tag_id=workshop, requests=["TRANSCRIPTION"]
    )
    assert media["transcriptStatus"] == "FAILED"

    _grant(client, workshop)
    assert _save_audio(client, workshop, media["id"]).json()["transcriptionsQueued"] == 1
    after = client.get(f"/api/media/{media['id']}").json()
    assert after["transcriptStatus"] == "QUEUED"


# ---- withdrawal ----------------------------------------------------------------------


async def test_withdrawing_consent_stops_a_transcription_already_queued(env, granted):
    """A CONSENT THAT CANNOT RECALL WHAT IT AUTHORISED IS A PREFERENCE, NOT A PERMISSION.

    ``dictation_consent``'s own docstring argues for the decision log with exactly this artisan: they
    agree on the 3rd, nine dictations go to a provider over the following week, and they change their
    mind on the 9th. A job queued under the grant sits in ``MediaProcessingJob`` waiting for the
    off-peak window — so without this the withdrawal would stop nothing that was already queued, and
    the recordings would leave AFTER the artisan said stop.
    """
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


# ---- the doors that bypass the queue -------------------------------------------------


async def test_the_admin_transcribe_now_button_is_refused_for_an_unasked_workshop(env, workshop):
    """``transcribe_media_now`` reaches ``transcribe_audio_bytes`` WITHOUT passing through
    ``_process_job``, which is the exact shape of the two doors this feature has already had to close.

    And an admin is not an exception. The observation that "the consent gate has never been a refusal
    an admin could meet" is an argument about PERMISSION — an admin can record a GRANTED decision
    themselves — and not an argument for sending. Recording the grant is attributed and logged;
    sending anyway is the same outcome with nobody's name on it.
    """
    media = _upload_audio(
        env["client"], env["user"], tag=DW_TAG, tag_id=workshop, requests=["TRANSCRIPTION"]
    )
    response = env["client"].post(f"/api/media/{media['id']}/transcribe-now")
    assert response.status_code == 409, response.text
    assert "Nobody has recorded yet" in response.json()["detail"]


async def test_refining_a_transcript_is_refused_too_and_names_openai(env, workshop):
    """The fourth door, and the one an audit that only looked for audio would miss: what leaves here
    is TEXT, and it goes to a different company. ``_verb_gate`` settled the argument for the five AI
    verbs — a transcript is the artisan's words with the audio compressed out of them."""
    media = _upload_audio(
        env["client"], env["user"], tag=DW_TAG, tag_id=workshop, requests=["TRANSCRIPTION"]
    )
    response = env["client"].post(
        f"/api/media/{media['id']}/refine-transcript", json={"translate": False}
    )
    assert response.status_code == 409, response.text
    assert "OpenAI's language model" in response.json()["detail"]


async def test_withdrawal_reaches_a_recording_that_no_stage_references_yet(env, granted):
    """THE CASE THE STAGE WALK ALONE MISSED, AND IT IS THE ORDINARY ORDER OF EVENTS.

    A phone uploads the file and only THEN writes its id into the stage, so between those two requests
    the recording is queued and no ``DwStageEntry`` mentions it. A withdrawal in that window found
    nothing to cancel, and the clip sat reading QUEUED on the transcripts screen — while the drain
    check would in fact have refused it, so nothing was ever going to be sent. What was broken was the
    visibility, which is the whole reason cancellation exists as well as the drain check.

    Found by a live request against the running API and not by any test, which is why this one exists:
    ``workshop_audio_media_ids`` now reads the ``designWorkshop`` link tag as well as the stage walk.
    """
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


# ---- the workshop a clip belongs to is not only the tag it arrived with ---------------


async def test_a_clip_a_stage_names_is_refused_even_when_it_carries_no_tag(env, workshop):
    """THE HOLE MEASURED ON THE RUNNING API, AND THE ORDINARY ORDER OF EVENTS PUT IT THERE.

    ``POST /media/complete`` for an AUDIO clip with no ``designWorkshop`` tag is
    NOT_WORKSHOP_MATERIAL and is queued — correctly, because at that instant nothing anywhere names a
    workshop. The clip is written into a stage field only afterwards: the phone uploads the file and
    THEN saves the stage. From that moment the stage says whose recording it is, and every gate that
    read only the tag went on answering "not workshop material".

    Reproduced against the live API before the fix, on a workshop whose ``dictationConsent`` is
    REFUSED: the queue drain handed the bytes to ElevenLabs, Deepgram and OpenAI in turn, ``POST
    /media/{id}/transcribe-now`` answered 200 and did the same, and ``POST
    /media/{id}/refine-transcript`` posted the artisan's transcript to OpenAI at DESIGNER rank. The
    two synchronous doors are asserted here because they answer BEFORE any provider is contacted,
    which is this module's own rule; ``resolve_from_stages`` is the same argument on all three.
    """
    client = env["client"]
    media = _upload_audio(client, env["user"], requests=[])
    assert media.get("linkedRecordType") in (None, ""), "the premise: no tag of its own"
    assert _save_audio(client, workshop, media["id"]).status_code == 200

    now = client.post(f"/api/media/{media['id']}/transcribe-now")
    assert now.status_code == 409, now.text
    assert "Nobody has recorded yet" in now.json()["detail"]

    assert client.post(
        f"/api/media/{media['id']}/transcript", json={"text": TRANSCRIPT}
    ).status_code == 200
    refine = client.post(f"/api/media/{media['id']}/refine-transcript", json={"translate": False})
    assert refine.status_code == 409, refine.text
    assert "OpenAI's language model" in refine.json()["detail"]


async def test_a_clip_no_stage_names_and_no_tag_is_still_transcribable(env):
    """THE OTHER HALF, AND THE ONE A CARELESS FAIL-CLOSED CHANGE BREAKS. 462 of the 528 AUDIO rows on
    this deployment carry no tag and 279 of them are transcribed — questionnaire interviews, whose
    artisans were asked a different question by a different consent process. A reverse lookup that
    found nothing has to mean "nothing in this module governs it", never "refuse"."""
    media = _upload_audio(env["client"], env["user"], requests=["TRANSCRIPTION"])
    assert media["transcriptStatus"] == "QUEUED"
    assert [j["jobType"] for j in _jobs(media)] == ["TRANSCRIPTION"]
    assert (media["transcriptError"] or "") == "", "a consent refusal was written onto an interview clip"


async def test_a_consented_workshops_stage_cannot_send_another_workshops_recording(env, granted):
    """WHOSE CONSENT IS IT. Measured on the running API before the fix: ``PUT
    /design-workshops/{A}/stages/{k}`` reported ``transcriptionsQueued: 1`` for a recording tagged to
    workshop **B**, A GRANTED and B REFUSED, because the caller-supplied id won over the row's own tag.

    A designer running two workshops in one cluster owns the media of both, so pasting the wrong id
    into the wrong stage is an ordinary slip — and the consent that governs a voice is the consent of
    the person whose voice it is. Every workshop that names the file has to permit the send.
    """
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
    """THE HALF OF A PRIVACY GATE THAT NO TEST WATCHED: that it still lets the permitted work happen.

    ``cancel_pending_transcriptions`` is unconditional about what it writes — every QUEUED/PROCESSING
    transcription of this workshop becomes FAILED and every clip is stamped with
    ``gate_refusal(REFUSED, MEDIA)``, *"This workshop's recordings may not be sent to the transcription
    service — that is the answer on record"*. It is therefore correct **only** behind the REFUSED guard
    in ``record_dictation_consent``. Called on a GRANTED decision it destroys the queue the grant was
    recorded in order to fill, and puts a sentence saying the artisan refused onto the recordings of an
    artisan who just agreed.

    IT WAS REACHED. The guard was dropped in this tree while the recordings' half of the gate was being
    built, and the whole suite stayed green: every other consent test records its answer BEFORE the clip
    is uploaded, so the cancellation runs against an empty queue and finds nothing to destroy. The order
    that breaks is the ordinary one — grant, record the artisan for an hour, then confirm the answer
    again or have a colleague record it — which no test performed.

    ASSERTED ON THE SENTENCES AND NOT ON THE STATUS, deliberately. ``app.main``'s lifespan starts a
    media-queue worker, so a job queued inside this module can be drained mid-test and legitimately
    reach FAILED with an object-storage error; pinning ``transcriptStatus == "QUEUED"`` would make this
    test a race. Neither refusal sentence is producible for a GRANTED workshop by any path — the drain
    gate re-reads the same consent — so their absence is the exact assertion, and it holds whenever the
    worker happens to run.
    """
    client = env["client"]
    media = _upload_audio(
        client, env["user"], tag=DW_TAG, tag_id=granted, requests=["TRANSCRIPTION"]
    )
    assert media["transcriptStatus"] == "QUEUED", "the premise: queued under the grant"

    _grant(client, granted)  # the artisan confirms again, or a colleague records the same answer

    after = client.get(f"/api/media/{media['id']}").json()
    assert "that is the answer on record" not in (after["transcriptError"] or ""), (
        "recording a GRANT stamped this workshop's own recording with the refusal sentence"
    )
    for job in _jobs(after):
        assert "was withdrawn" not in (job.get("error") or ""), (
            "recording a GRANT cancelled the transcription that grant was recorded to permit"
        )
