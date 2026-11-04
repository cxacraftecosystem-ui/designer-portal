import os
import uuid
from datetime import UTC, datetime

import pytest

import app.services.stage_definitions  # noqa: F401  - installs the registry
from app.core.db import db
from app.core.security import create_access_token, hash_password
from app.services import records
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

STRANGER_TRANSCRIPT = (
    "**Interviewer:** THE-STRANGERS-PRIVATE-WORDS how is the warp set?\n"
    "**Interviewee:** Before dawn, when the yarn is still cool.\n"
)
OWN_TRANSCRIPT = "**Interviewer:** MY-OWN-WORDS what dye is that?\n**Interviewee:** Indigo.\n"
TEAM_TRANSCRIPT = (
    "**Interviewer:** OUR-SHARED-WORKSHOP how long is the pit loom?\n"
    "**Interviewee:** Four hands and a span.\n"
)

TEAM_PHOTO_URL = "https://cdn.example.test/design-workshop/team-loom.jpg"
OTHER_PHOTO_URL = "https://cdn.example.test/design-workshop/other-loom.jpg"
PLAIN_PHOTO_URL = "https://cdn.example.test/design-workshop/plain-loom.jpg"
DEAD_PHOTO_URL = "https://cdn.example.test/design-workshop/dead-loom.jpg"


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
        downloader = await db.user.create(data={
            "email": f"ent-downloader-{stamp}@example.org", "name": "Ent Downloader",
            "role": "RESEARCHER", "passwordHash": hash_password("unused"),
            "canDownloadDataset": True,
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
        colleague = await db.user.create(data={
            "email": f"ent-colleague-{stamp}@example.org", "name": "Ent Colleague",
            "role": "DESIGNER", "passwordHash": hash_password("unused"),
        })
        team_workshop = await db.designworkshop.create(data={
            "title": f"Pit loom fortnight {stamp}", "createdById": designer.id,
        })
        await db.designworkshopviewer.create(data={
            "designWorkshopId": team_workshop.id, "userId": colleague.id,
            "grantedById": admin.id,
        })
        team_audio = await _media(designer.id, stamp, "AUDIO", "team-interview.m4a",
                                  transcriptStatus="COMPLETED",
                                  transcriptText=TEAM_TRANSCRIPT,
                                  linkedRecordType="designWorkshop",
                                  linkedRecordId=team_workshop.id)
        team_photo = await _media(designer.id, stamp, "IMAGE", "team-loom.jpg",
                                  url=TEAM_PHOTO_URL,
                                  linkedRecordType="designWorkshop",
                                  linkedRecordId=team_workshop.id)
        other_workshop = await db.designworkshop.create(data={
            "title": f"Bamboo fortnight {stamp}", "createdById": designer.id,
        })
        other_photo = await _media(designer.id, stamp, "IMAGE", "other-loom.jpg",
                                   url=OTHER_PHOTO_URL,
                                   linkedRecordType="designWorkshop",
                                   linkedRecordId=other_workshop.id)
        plain_photo = await _media(designer.id, stamp, "IMAGE", "plain-loom.jpg",
                                   url=PLAIN_PHOTO_URL)
        dead_workshop = await db.designworkshop.create(data={
            "title": f"Abandoned fortnight {stamp}", "createdById": designer.id,
            "deletedAt": datetime.now(UTC),
        })
        await db.designworkshopviewer.create(data={
            "designWorkshopId": dead_workshop.id, "userId": colleague.id,
            "grantedById": admin.id,
        })
        dead_photo = await _media(designer.id, stamp, "IMAGE", "dead-loom.jpg",
                                  url=DEAD_PHOTO_URL,
                                  linkedRecordType="designWorkshop",
                                  linkedRecordId=dead_workshop.id)
        for account in (designer, stranger, colleague):
            await db.designerroster.create(data={
                "email": account.email,
                "fullName": account.name,
                "institution": "Directorate of Handicrafts",
                "isActive": True,
                "addedById": admin.id,
            })
    finally:
        await db.disconnect()

    with TestClient(app) as client:
        yield {
            "client": client,
            "designer": create_access_token(subject=designer.id),
            "admin": create_access_token(subject=admin.id),
            "colleague": create_access_token(subject=colleague.id),
            "stranger": create_access_token(subject=stranger.id),
            "downloader": create_access_token(subject=downloader.id),
            "downloader_id": downloader.id,
            "designer_id": designer.id,
            "admin_id": admin.id,
            "stranger_id": stranger.id,
            "own_photo": own_photo.id,
            "foreign_photo": foreign_photo.id,
            "own_audio": own_audio.id,
            "foreign_audio": foreign_audio.id,
            "team_workshop": team_workshop.id,
            "team_audio": team_audio.id,
            "team_photo": team_photo.id,
            "other_photo": other_photo.id,
            "plain_photo": plain_photo.id,
            "dead_photo": dead_photo.id,
        }


class _As:
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


@pytest.fixture
def colleague_client(env):
    return _As(env["client"], env["colleague"])


@pytest.fixture
def downloader_client(env):
    return _As(env["client"], env["downloader"])


@pytest.fixture
def stranger_client(env):
    return _As(env["client"], env["stranger"])


def _workshop(env, owner: str = "designer_id") -> str:
    admin = _As(env["client"], env["admin"])
    response = admin.post("/api/design-workshops", json={"title": "Entitlement test workshop"})
    assert response.status_code == 201, response.text
    workshop_id = response.json()["id"]
    if owner != "admin_id":
        granted = admin.put(
            f"/api/design-workshops/{workshop_id}/viewers", json={"userIds": [env[owner]]}
        )
        assert granted.status_code == 200, granted.text
    return workshop_id


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



def test_a_photograph_uploaded_by_someone_else_never_reaches_the_report(env, client):
    workshop_id = _workshop(env)
    _save(client, workshop_id, PHOTO_STAGE, PHOTO_ENTITY,
          {"workshopTitle": "Ikat", PHOTO_FIELD: env["foreign_photo"]})

    payload = _preview(client, workshop_id)
    assert env["foreign_photo"] not in str(payload["blocks"]), (
        "the report still carries a photograph the caller may not download"
    )


def test_the_withheld_photograph_is_reported_rather_than_dropped_in_silence(env, client):
    workshop_id = _workshop(env)
    _save(client, workshop_id, PHOTO_STAGE, PHOTO_ENTITY,
          {"workshopTitle": "Ikat", PHOTO_FIELD: env["foreign_photo"]})

    warnings = " ".join(_preview(client, workshop_id)["warnings"])
    assert "could not be included" in warnings, warnings


def test_the_designers_own_photograph_still_reaches_the_report(env, client):
    workshop_id = _workshop(env)
    _save(client, workshop_id, PHOTO_STAGE, PHOTO_ENTITY,
          {"workshopTitle": "Ikat", PHOTO_FIELD: env["own_photo"]})

    payload = _preview(client, workshop_id)
    assert env["own_photo"] in str(payload["blocks"])
    assert "could not be included" not in " ".join(payload["warnings"])


def test_an_admin_may_still_be_handed_every_file(env, admin_client):
    workshop_id = _workshop(env, "admin_id")
    _save(admin_client, workshop_id, PHOTO_STAGE, PHOTO_ENTITY,
          {"workshopTitle": "Ikat", PHOTO_FIELD: env["foreign_photo"]})

    payload = _preview(admin_client, workshop_id)
    assert env["foreign_photo"] in str(payload["blocks"])


def _sources(client, workshop_id: str) -> dict:
    response = client.get(f"/api/design-workshops/{workshop_id}/report/sources")
    assert response.status_code == 200, response.text
    return response.json()


def test_the_offline_report_sources_carry_no_photograph_the_caller_may_not_download(env, client):
    workshop_id = _workshop(env)
    _save(client, workshop_id, PHOTO_STAGE, PHOTO_ENTITY,
          {"workshopTitle": "Ikat", PHOTO_FIELD: env["foreign_photo"]})
    payload = _sources(client, workshop_id)
    assert env["foreign_photo"] not in payload["media"]
    assert "could not be included" in " ".join(payload["warnings"]["media"])

    _save(client, workshop_id, PHOTO_STAGE, PHOTO_ENTITY,
          {"workshopTitle": "Ikat", PHOTO_FIELD: env["own_photo"]})
    payload = _sources(client, workshop_id)
    assert env["own_photo"] in payload["media"]
    assert payload["media"][env["own_photo"]]["source"] == env["own_photo"]
    assert set(payload) >= {"references", "districtPoints", "questionnaires", "transcripts", "aiLayers"}


def test_the_offline_report_sources_are_refused_to_a_stranger(env, client, stranger_client):
    workshop_id = _workshop(env)
    response = stranger_client.get(f"/api/design-workshops/{workshop_id}/report/sources")
    assert response.status_code in (403, 404), response.text



def test_a_strangers_transcript_is_not_listed_by_the_transcripts_endpoint(env, client):
    workshop_id = _workshop(env)
    _save(client, workshop_id, AUDIO_STAGE, AUDIO_ENTITY, {AUDIO_FIELD: env["foreign_audio"]})

    response = client.get(f"/api/design-workshops/{workshop_id}/transcripts")
    assert response.status_code == 200, response.text
    assert response.json()["total"] == 0
    assert "THE-STRANGERS-PRIVATE-WORDS" not in response.text


def test_a_strangers_transcript_does_not_come_back_onto_the_stage(env, client):
    workshop_id = _workshop(env)
    _save(client, workshop_id, AUDIO_STAGE, AUDIO_ENTITY, {AUDIO_FIELD: env["foreign_audio"]})

    response = client.get(f"/api/design-workshops/{workshop_id}")
    assert response.status_code == 200, response.text
    assert env["foreign_audio"] not in response.json()["transcripts"]
    assert "THE-STRANGERS-PRIVATE-WORDS" not in response.text


def test_the_designers_own_recording_is_still_listed_and_still_comes_back(env, client):
    workshop_id = _workshop(env)
    _save(client, workshop_id, AUDIO_STAGE, AUDIO_ENTITY, {AUDIO_FIELD: env["own_audio"]})

    listed = client.get(f"/api/design-workshops/{workshop_id}/transcripts")
    assert listed.status_code == 200, listed.text
    assert listed.json()["total"] == 1

    stage = client.get(f"/api/design-workshops/{workshop_id}")
    assert env["own_audio"] in stage.json()["transcripts"]
    assert "MY-OWN-WORDS" in stage.text


def test_a_granted_co_designer_is_shown_the_workshops_own_recordings(
    env, colleague_client
):
    _save(colleague_client, env["team_workshop"], AUDIO_STAGE, AUDIO_ENTITY,
          {AUDIO_FIELD: env["team_audio"]})

    response = colleague_client.get(
        f"/api/design-workshops/{env['team_workshop']}/transcripts"
    )
    assert response.status_code == 200, response.text
    assert response.json()["total"] == 1
    assert "OUR-SHARED-WORKSHOP" in response.text


def test_a_designer_with_no_grant_is_still_refused_the_same_recording(
    env, stranger_client
):
    workshop_id = _workshop(env, "stranger_id")
    _save(stranger_client, workshop_id, AUDIO_STAGE, AUDIO_ENTITY,
          {AUDIO_FIELD: env["team_audio"]})

    response = stranger_client.get(f"/api/design-workshops/{workshop_id}/transcripts")
    assert response.status_code == 200, response.text
    assert response.json()["total"] == 0
    assert "OUR-SHARED-WORKSHOP" not in response.text


def test_the_report_path_refuses_a_strangers_recording_and_says_so(env, client):
    mine = _workshop(env)
    _save(client, mine, AUDIO_STAGE, AUDIO_ENTITY, {AUDIO_FIELD: env["own_audio"]})
    _save(client, mine, "REPORT_GENERATION", "reportSettings", {"includeTranscripts": True})
    own_warnings = " ".join(_preview(client, mine)["warnings"])
    assert "transcript annexure" not in own_warnings, own_warnings

    theirs = _workshop(env)
    _save(client, theirs, AUDIO_STAGE, AUDIO_ENTITY, {AUDIO_FIELD: env["foreign_audio"]})
    _save(client, theirs, "REPORT_GENERATION", "reportSettings", {"includeTranscripts": True})
    their_warnings = " ".join(_preview(client, theirs)["warnings"])
    assert "could not be included in the transcript annexure" in their_warnings, their_warnings



HOSTILE_URL = "https://attacker.example/portal-login"


def test_a_caller_supplied_media_url_is_ignored(env, client):
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


PLANTED_TRANSCRIPT = "**Interviewer:** PLANTED-BY-THE-CALLER did you agree to this?\n"


def _every_string_in(row: dict) -> str:
    import json

    return json.dumps(row, default=str)


def _complete_audio_with_transcript(client, user_id: str) -> dict:
    key = f"media/{user_id}/{uuid.uuid4().hex}/planted.webm"
    response = client.post("/api/media/complete", json={
        "originalFilename": "planted.webm",
        "mediaType": "AUDIO",
        "mimeType": "audio/webm",
        "sizeBytes": 2048,
        "objectKey": key,
        "transcriptText": PLANTED_TRANSCRIPT,
        "transcriptSummary": "A summary nobody transcribed.",
        "transcriptStatus": "COMPLETED",
        "transcriptError": None,
    })
    assert response.status_code == 201, response.text
    return response.json()


def test_a_caller_supplied_transcript_is_ignored(env, client):
    created = _complete_audio_with_transcript(client, env["designer_id"])

    row = _media_row(client, created["id"])
    assert not (row.get("transcriptText") or ""), row.get("transcriptText")
    assert not (row.get("transcriptSummary") or ""), row.get("transcriptSummary")
    assert "PLANTED-BY-THE-CALLER" not in _every_string_in(row)

    assert (row.get("transcriptStatus") or None) != "COMPLETED", row.get("transcriptStatus")


def test_the_transcript_fields_are_still_ACCEPTED_so_installed_clients_keep_uploading(env, client):
    created = _complete_audio_with_transcript(client, env["designer_id"])
    assert created["id"], "the row was created, not refused"
    assert created["mediaType"] == "AUDIO"


def test_a_whole_shipped_client_body_still_succeeds_and_the_server_values_win(env, client):
    key = f"media/{env['designer_id']}/{uuid.uuid4().hex}/whole-body.webm"
    response = client.post("/api/media/complete", json={
        "originalFilename": "whole-body.webm",
        "mediaType": "AUDIO",
        "mimeType": "audio/webm",
        "sizeBytes": 4096,
        "objectKey": key,
        "bucket": "design-workshop",
        "caption": "Interview with the master weaver",
        "recordedAt": "2026-08-28T09:15:00+00:00",
        "recordedTimezone": "Asia/Kolkata",
        "processingRequests": [],
        "url": HOSTILE_URL,
        "checksum": "sha256:0123456789abcdef",
        "transcriptText": PLANTED_TRANSCRIPT,
        "transcriptSummary": "A summary nobody transcribed.",
        "transcriptStatus": "COMPLETED",
        "transcriptError": None,
    })
    assert response.status_code == 201, response.text

    created = response.json()
    assert created["url"] != HOSTILE_URL
    assert created["url"] is None or created["url"].endswith(key), created["url"]

    row = _media_row(client, created["id"])
    assert not (row.get("transcriptText") or ""), row.get("transcriptText")
    assert not (row.get("transcriptSummary") or ""), row.get("transcriptSummary")
    assert (row.get("transcriptStatus") or None) != "COMPLETED", row.get("transcriptStatus")
    assert "PLANTED-BY-THE-CALLER" not in _every_string_in(row)
    assert row["url"] != HOSTILE_URL
    assert row["caption"] == "Interview with the master weaver"
    assert row["checksum"] == "sha256:0123456789abcdef"



def _media_row(client, media_id: str) -> dict:
    response = client.get(f"/api/media/{media_id}")
    assert response.status_code == 200, response.text
    return response.json()


def test_a_strangers_transcript_is_withheld_by_the_media_detail_route(env, client):
    row = _media_row(client, env["foreign_audio"])
    assert row["id"] == env["foreign_audio"], "the ROW still travels; only the bytes are withheld"
    assert "transcriptText" not in row, row.get("transcriptText")
    assert "transcriptSummary" not in row
    assert "url" not in row


def test_a_strangers_transcript_is_withheld_by_the_media_list_route(env, client):
    response = client.get("/api/media?mediaType=AUDIO&pageSize=100")
    assert response.status_code == 200, response.text
    assert "THE-STRANGERS-PRIVATE-WORDS" not in response.text
    ids = {item["id"] for item in response.json()["items"]}
    assert env["foreign_audio"] in ids, "the row must still be listed — this is a read-open repository"


def test_the_uploaders_own_transcript_still_travels(env, client):
    row = _media_row(client, env["own_audio"])
    assert row["transcriptText"] == OWN_TRANSCRIPT


def test_an_admin_still_reads_every_transcript(env, admin_client):
    row = _media_row(admin_client, env["foreign_audio"])
    assert row["transcriptText"] == STRANGER_TRANSCRIPT


def test_the_dataset_download_boolean_does_not_hand_over_every_url(env, downloader_client):
    row = _media_row(downloader_client, env["foreign_photo"])
    assert "url" not in row, row.get("url")
    assert "publicUrl" not in row
    assert "objectKey" not in row, "objectKey IS the URL, one string concatenation later"


def test_the_dataset_download_boolean_does_not_hand_over_every_transcript(env, downloader_client):
    row = _media_row(downloader_client, env["foreign_audio"])
    assert "transcriptText" not in row, row.get("transcriptText")


def test_the_dataset_download_holder_keeps_their_own_uploads(env, downloader_client, client):
    key = f"media/{env['downloader_id']}/{uuid.uuid4().hex}/theirs.jpg"
    created = downloader_client.post("/api/media/complete", json={
        "originalFilename": "theirs.jpg",
        "mediaType": "IMAGE",
        "mimeType": "image/jpeg",
        "sizeBytes": 1,
        "objectKey": key,
    })
    assert created.status_code == 201, created.text
    own_id = created.json()["id"]

    row = _media_row(downloader_client, own_id)
    assert "objectKey" in row, "their own upload must still carry its handle"
    assert "objectKey" not in _media_row(client, own_id)


UNIT_WORKSHOP = "dw-the-one-we-run-together"
UNIT_UPLOADER = "user-the-colleague-who-uploaded-it"
UNIT_VIEWER = "user-the-co-designer-reading-it"
UNIT_URL = "https://cdn.example.test/media/pit-loom.jpg"


def _encoded_media(**extra) -> dict:
    return {
        "id": "media-1",
        "originalFilename": "pit-loom.jpg",
        "mediaType": "IMAGE",
        "uploadedById": UNIT_UPLOADER,
        "objectKey": "media/user-the-colleague-who-uploaded-it/pit-loom.jpg",
        "url": UNIT_URL,
        "publicUrl": UNIT_URL,
        "transcriptText": TEAM_TRANSCRIPT,
        "transcriptSummary": "A pit loom, four hands and a span.",
        **extra,
    }


def _tagged_media(workshop_id: str = UNIT_WORKSHOP, **extra) -> dict:
    return _encoded_media(
        linkedRecordType="designWorkshop", linkedRecordId=workshop_id, **extra
    )


def _takeable(node: dict) -> set[str]:
    return {key for key in records._MEDIA_TAKEABLE_KEYS if key in node}



def test_a_co_designer_keeps_the_bytes_of_a_file_tagged_to_their_own_workshop():
    node = records._redact_sensitive(
        _tagged_media(),
        viewer_id=UNIT_VIEWER,
        unmasked=False,
        media_urls={UNIT_VIEWER},
        media_workshops=frozenset({UNIT_WORKSHOP}),
    )

    assert _takeable(node) == set(records._MEDIA_TAKEABLE_KEYS)
    assert node["url"] == UNIT_URL


def test_a_file_tagged_to_a_workshop_this_account_is_not_on_is_still_withheld():
    node = records._redact_sensitive(
        _tagged_media("dw-somebody-elses-fortnight"),
        viewer_id=UNIT_VIEWER,
        unmasked=False,
        media_urls={UNIT_VIEWER},
        media_workshops=frozenset({UNIT_WORKSHOP}),
    )

    assert _takeable(node) == set()


def test_the_same_uploaders_untagged_file_is_still_withheld():
    node = records._redact_sensitive(
        _encoded_media(),
        viewer_id=UNIT_VIEWER,
        unmasked=False,
        media_urls={UNIT_VIEWER},
        media_workshops=frozenset({UNIT_WORKSHOP}),
    )

    assert "url" not in node, node.get("url")
    assert "objectKey" not in node, "objectKey IS the URL, one string concatenation later"
    assert "transcriptText" not in node
    assert _takeable(node) == set()


@pytest.mark.parametrize(
    "tag_columns",
    [
        pytest.param({}, id="neither-column"),
        pytest.param({"linkedRecordType": "designWorkshop"}, id="a-type-and-no-id"),
        pytest.param({"linkedRecordId": UNIT_WORKSHOP}, id="an-id-and-no-type"),
        pytest.param(
            {"linkedRecordType": "designWorkshop", "linkedRecordId": None},
            id="tagged-to-nothing",
        ),
        pytest.param(
            {"linkedRecordType": "workshop", "linkedRecordId": UNIT_WORKSHOP},
            id="a-different-record-type",
        ),
    ],
)
def test_a_media_node_without_a_matching_tag_pair_fails_closed(tag_columns):
    node = records._redact_sensitive(
        _encoded_media(**tag_columns),
        viewer_id=UNIT_VIEWER,
        unmasked=False,
        media_urls={UNIT_VIEWER},
        media_workshops=frozenset({UNIT_WORKSHOP}),
    )

    assert _takeable(node) == set()


def test_public_encode_carries_the_workshops_only_when_the_call_site_passes_them():
    viewer = {"id": UNIT_VIEWER, "role": "DESIGNER"}

    passed = records.public_encode(
        _tagged_media(),
        viewer,
        media_urls={UNIT_VIEWER},
        media_workshops=frozenset({UNIT_WORKSHOP}),
    )
    assert passed["url"] == UNIT_URL
    assert _takeable(passed) == set(records._MEDIA_TAKEABLE_KEYS)

    omitted = records.public_encode(_tagged_media(), viewer, media_urls={UNIT_VIEWER})
    assert _takeable(omitted) == set(), "omitting the workshops must withhold, never widen"


def test_the_workshop_scope_reaches_media_nested_inside_a_record():
    payload = {
        "id": UNIT_WORKSHOP,
        "title": "Pit loom fortnight",
        "media": [_tagged_media(), _tagged_media("dw-somebody-elses-fortnight")],
    }

    records._redact_sensitive(
        payload,
        viewer_id=UNIT_VIEWER,
        unmasked=False,
        media_urls={UNIT_VIEWER},
        media_workshops=frozenset({UNIT_WORKSHOP}),
    )

    ours, theirs = payload["media"]
    assert _takeable(ours) == set(records._MEDIA_TAKEABLE_KEYS)
    assert _takeable(theirs) == set()


async def test_professor_and_above_pays_for_no_workshop_query(monkeypatch):
    asked = []

    async def _record(user_id: str) -> list[str]:
        asked.append(user_id)
        return ["dw-a-professor-was-asked-about-this"]

    monkeypatch.setattr(records, "_design_workshop_media_ids", _record)

    scope = await records.media_url_scope({"id": "user-professor", "role": "PROFESSOR"})

    assert scope == (records.ALL_MEDIA_URLS, frozenset())
    assert asked == [], "a professor's URL scope must not cost a workshop query"


async def test_the_scope_is_composed_from_the_uploader_set_rather_than_replacing_it(monkeypatch):
    asked = []
    granting_owner = "user-who-granted-them-a-tier"

    async def _owners(viewer):
        return {UNIT_VIEWER, granting_owner}

    async def _workshops(user_id: str) -> list[str]:
        asked.append(user_id)
        return [UNIT_WORKSHOP, "dw-the-other-one-we-run"]

    monkeypatch.setattr(records, "media_url_owners", _owners)
    monkeypatch.setattr(records, "_design_workshop_media_ids", _workshops)

    uploaders, workshops = await records.media_url_scope(
        {"id": UNIT_VIEWER, "role": "DESIGNER"}
    )

    assert uploaders == {UNIT_VIEWER, granting_owner}
    assert workshops == frozenset({UNIT_WORKSHOP, "dw-the-other-one-we-run"})
    assert asked == [UNIT_VIEWER]


async def test_a_viewer_with_no_id_gets_neither_half(monkeypatch):
    asked = []

    async def _record(user_id: str) -> list[str]:
        asked.append(user_id)
        return []

    monkeypatch.setattr(records, "_design_workshop_media_ids", _record)

    assert await records.media_url_scope({"role": "DESIGNER"}) == (set(), frozenset())
    assert asked == []



def test_a_co_designer_is_handed_the_url_of_the_workshops_own_photograph(env, colleague_client):
    row = _media_row(colleague_client, env["team_photo"])

    assert row["id"] == env["team_photo"]
    assert row["url"] == TEAM_PHOTO_URL
    assert "objectKey" in row, "the key travels with the URL; one is the other"


def test_the_media_route_now_agrees_with_the_transcripts_route_for_the_same_account(
    env, colleague_client
):
    row = _media_row(colleague_client, env["team_audio"])

    assert row["transcriptText"] == TEAM_TRANSCRIPT
    assert "OUR-SHARED-WORKSHOP" in row["transcriptText"]


def test_a_photograph_of_a_workshop_this_account_is_not_on_is_refused(env, colleague_client):
    row = _media_row(colleague_client, env["other_photo"])

    assert row["id"] == env["other_photo"], "the row still travels; only the bytes are withheld"
    assert "url" not in row, row.get("url")
    assert "objectKey" not in row


def test_the_uploaders_untagged_photograph_is_still_withheld_through_the_route(
    env, colleague_client
):
    row = _media_row(colleague_client, env["plain_photo"])

    assert "url" not in row, row.get("url")
    assert "objectKey" not in row


def test_a_soft_deleted_workshop_stops_conferring_the_url(env, colleague_client):
    row = _media_row(colleague_client, env["dead_photo"])

    assert "url" not in row, row.get("url")
    assert "objectKey" not in row


def test_a_designer_not_on_that_workshop_is_refused_the_same_photograph(env, stranger_client):
    row = _media_row(stranger_client, env["team_photo"])

    assert row["id"] == env["team_photo"]
    assert "url" not in row, row.get("url")
    assert "objectKey" not in row
