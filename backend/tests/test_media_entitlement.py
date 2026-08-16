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
# The recording a co-designer is SUPPOSED to be able to see: made by their colleague, filed under
# the workshop the two of them run together.
TEAM_TRANSCRIPT = (
    "**Interviewer:** OUR-SHARED-WORKSHOP how long is the pit loom?\n"
    "**Interviewee:** Four hands and a span.\n"
)


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
        # A RESEARCHER (rank 30) HOLDING THE GRANTABLE DATASET-DOWNLOAD BOOLEAN. This is the account
        # ``records.media_url_owners`` used to widen to every URL in the repository on the premise
        # that they "may already download the whole repository" — a premise the two download
        # surfaces contradict for this exact person. Below PROFESSOR on purpose; at Professor or
        # above the rank arm answers first and the test would pass against the unfixed code.
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
        # THE TEAM'S OWN WORKSHOP, made here rather than through the API because the grant that
        # goes with it is a row an admin writes and the whole point is that the COLLEAGUE did not
        # create the workshop and did not upload the recording. `linkedRecordType`/`linkedRecordId`
        # are the tag both clients file every design-workshop upload under — see
        # `dictation_consent.MEDIA_TAG` — and they are the only link between a MediaFile and a
        # DesignWorkshop that exists, there being no foreign key.
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
        # ROSTER ROWS, WHICH ARRIVED WITH THE CREATE RULE. `_workshop` now opens a workshop as the
        # admin and grants the designer who will work in it, and `replace_viewers` refuses to grant
        # an account the ACTIVE designer roster does not admit — a 422, not a silent skip. Without
        # these rows every workshop in this module would exist and be unreachable by the account the
        # test is about, and the entitlement assertions would all fail as 404s.
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

    # ONE TestClient, two tokens. Two nested clients each run the app's lifespan against the SAME
    # module-level Prisma client, and the second teardown disconnects it under the first — which
    # hangs rather than failing. The account is chosen per request instead.
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
            # Needed by `_workshop`: a workshop is now OPENED by an admin and GRANTED to the account
            # that will work in it, so the helper has to name that account by id.
            "admin_id": admin.id,
            "stranger_id": stranger.id,
            "own_photo": own_photo.id,
            "foreign_photo": foreign_photo.id,
            "own_audio": own_audio.id,
            "foreign_audio": foreign_audio.id,
            "team_workshop": team_workshop.id,
            "team_audio": team_audio.id,
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


@pytest.fixture
def colleague_client(env):
    """The co-designer: a DESIGNER holding a viewer grant on somebody else's workshop."""
    return _As(env["client"], env["colleague"])


@pytest.fixture
def downloader_client(env):
    """A RESEARCHER holding ``canDownloadDataset`` — below Professor, and that is the whole point."""
    return _As(env["client"], env["downloader"])


@pytest.fixture
def stranger_client(env):
    """A DESIGNER with no grant on the team's workshop — the control on the clause below."""
    return _As(env["client"], env["stranger"])


def _workshop(env, owner: str = "designer_id") -> str:
    """A workshop the account named by ``owner`` may work in, opened the way workshops are opened.

    THE ADMIN CREATES IT AND GRANTS THE ACCOUNT. This helper used to post as whichever client it was
    handed, which is how designers made workshops until only admins and the master admin could start
    one (``can_create_design_workshops``): a workshop is the container a fortnight of records lives
    in and the unit the ministry indexes, not a record. Posting as a designer now answers 403, so
    every test in this module would have died on its setup line for a reason that has nothing to do
    with media entitlement.

    ``owner`` is an id key in ``env`` rather than a client, because the grant needs a user id and
    ``_As`` deliberately hides it. An owner of ``"admin_id"`` skips the grant: the admin creating it
    already reaches it through ``createdById`` and through ``is_admin``, and ``replace_viewers``
    drops the creator from the set anyway.

    NOTE FOR ANYONE READING AN ENTITLEMENT FAILURE HERE: the workshop's ``createdById`` is now the
    ADMIN, not the designer. Nothing in this module asserts on it — media entitlement is decided per
    FILE by who uploaded it, never by who owns the workshop, which is the distinction the whole file
    exists to pin — but a test that starts depending on the creator will be depending on the admin.
    """
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


# --------------------------------------------------------------------------------------
# 1. Photographs
# --------------------------------------------------------------------------------------


def test_a_photograph_uploaded_by_someone_else_never_reaches_the_report(env, client):
    """THE LEAK. A designer pastes a stranger's media id into their own workshop's cover photo.

    Before the fix the resolver looked the id up unfiltered, the builder placed the ImageRef and the
    generated .docx carried the stranger's photograph. The id must now appear nowhere in the
    document the preview describes.
    """
    workshop_id = _workshop(env)
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
    workshop_id = _workshop(env)
    _save(client, workshop_id, PHOTO_STAGE, PHOTO_ENTITY,
          {"workshopTitle": "Ikat", PHOTO_FIELD: env["foreign_photo"]})

    warnings = " ".join(_preview(client, workshop_id)["warnings"])
    assert "could not be included" in warnings, warnings


def test_the_designers_own_photograph_still_reaches_the_report(env, client):
    """The other half of the fix, and the one a blanket refusal would break.

    Without this the entitlement could be "resolve nothing" and the test above would still pass.
    """
    workshop_id = _workshop(env)
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
    workshop_id = _workshop(env, "admin_id")
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
    workshop_id = _workshop(env)
    _save(client, workshop_id, AUDIO_STAGE, AUDIO_ENTITY, {AUDIO_FIELD: env["foreign_audio"]})

    response = client.get(f"/api/design-workshops/{workshop_id}/transcripts")
    assert response.status_code == 200, response.text
    assert response.json()["total"] == 0
    assert "THE-STRANGERS-PRIVATE-WORDS" not in response.text


def test_a_strangers_transcript_does_not_come_back_onto_the_stage(env, client):
    """The stage read carries the transcript text keyed by media id, so it is a second way out."""
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
    """THE REGRESSION, and the mirror image of the leak above: a refusal aimed at the wrong person.

    ``owned_or_granted_where`` admitted two things — your own uploads, and uploads by somebody who
    has given you a DataAccessGrant. A ``DesignWorkshopViewer`` row is NEITHER, so the co-designer
    the grant exists for was told the workshop had no recordings at all: ``{"items": [], "total":
    0}`` over interviews their own colleague uploaded to their own workshop. That is an empty list
    reading as "nothing exists" when it means "withheld from you", on the one screen whose stated
    job is to show a designer what they are about to append to a document going to a ministry —
    and the report generator, reading the same rows, would then tell them "1 recording(s) could not
    be included", so the two screens contradicted each other.

    The stage is saved BY THE COLLEAGUE, which is the shape of the real failure: the grant already
    carried stage writes, so they could name the recording on the stage and then be refused the
    transcript of the file they had just referenced.
    """
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
    """The control, and the reason the new clause is scoped to the TAG rather than to the caller.

    The same recording, the same tag, a designer who is on no workshop it names. If this passes
    while the test above passes, the widening is "the recordings of a workshop I may open" and not
    "any recording anybody filed under any workshop" — which is the version of this fix that would
    have handed every design workshop's audio to every designer in the repository.
    """
    workshop_id = _workshop(env, "stranger_id")
    _save(stranger_client, workshop_id, AUDIO_STAGE, AUDIO_ENTITY,
          {AUDIO_FIELD: env["team_audio"]})

    response = stranger_client.get(f"/api/design-workshops/{workshop_id}/transcripts")
    assert response.status_code == 200, response.text
    assert response.json()["total"] == 0
    assert "OUR-SHARED-WORKSHOP" not in response.text


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


# --------------------------------------------------------------------------------------
# 4. The media surface itself: the transcript column, and the dataset-download boolean
# --------------------------------------------------------------------------------------
#
# Everything above tests the DESIGN-WORKSHOP surface. Both defects below are on ``GET /media`` and
# ``GET /media/{id}``, which are the widest reads in the application, and each of them undid a
# control the tests above prove holds.
#
# (a) ``transcriptText`` was in none of the three redaction lists, and ``records.py``'s own banner
#     said so on purpose: "the ROW still travels for everybody: the filename, the type, the caption,
#     THE TRANSCRIPT". So the co-designer refused a colleague's recording by
#     ``load_transcript_items`` got it back by lifting the media id out of the stage he can already
#     edit and calling ``GET /api/media/{id}`` — and a CROWDSOURCE_VOLUNTEER at the authentication
#     floor could page the whole repository's interviews as text. Two live rules, and the
#     permissive one won on every path a client had.
#
# (b) ``media_url_owners`` and ``public_encode``'s default both returned ALL_MEDIA_URLS for a holder
#     of the grantable ``canDownloadDataset`` boolean. ``/data/media/{id}/download`` and
#     ``/export/dataset`` refuse that same account the same files, saying why in a comment:
#     "the permission means 'download the data you can SEE'". The URLs handed out carry no expiry
#     and no auth, so they survive revocation of the permission, of the grant, and of the account.


def _media_row(client, media_id: str) -> dict:
    response = client.get(f"/api/media/{media_id}")
    assert response.status_code == 200, response.text
    return response.json()


def test_a_strangers_transcript_is_withheld_by_the_media_detail_route(env, client):
    """THE SECOND DOOR. The design-workshop gate is only worth having if this one is shut too."""
    row = _media_row(client, env["foreign_audio"])
    assert row["id"] == env["foreign_audio"], "the ROW still travels; only the bytes are withheld"
    assert "transcriptText" not in row, row.get("transcriptText")
    assert "transcriptSummary" not in row
    assert "url" not in row


def test_a_strangers_transcript_is_withheld_by_the_media_list_route(env, client):
    """The list is the reachable version: one request, a hundred interviews a page."""
    response = client.get("/api/media?mediaType=AUDIO&pageSize=100")
    assert response.status_code == 200, response.text
    assert "THE-STRANGERS-PRIVATE-WORDS" not in response.text
    ids = {item["id"] for item in response.json()["items"]}
    assert env["foreign_audio"] in ids, "the row must still be listed — this is a read-open repository"


def test_the_uploaders_own_transcript_still_travels(env, client):
    """The half a blanket refusal would break: a designer must still read their own recording."""
    row = _media_row(client, env["own_audio"])
    assert row["transcriptText"] == OWN_TRANSCRIPT


def test_an_admin_still_reads_every_transcript(env, admin_client):
    """Rank-aware, like every other arm of this predicate — ADMIN outranks PROFESSOR."""
    row = _media_row(admin_client, env["foreign_audio"])
    assert row["transcriptText"] == STRANGER_TRANSCRIPT


def test_the_dataset_download_boolean_does_not_hand_over_every_url(env, downloader_client):
    """THE WIDENING. One account-level boolean bypassed the per-uploader grant system for the URL of
    every file in the repository, on the two widest read endpoints, while the two download endpoints
    written for that exact concern refused the same account."""
    row = _media_row(downloader_client, env["foreign_photo"])
    assert "url" not in row, row.get("url")
    assert "publicUrl" not in row
    assert "objectKey" not in row, "objectKey IS the URL, one string concatenation later"


def test_the_dataset_download_boolean_does_not_hand_over_every_transcript(env, downloader_client):
    """Both keys move together, because both are the recording rather than a description of it."""
    row = _media_row(downloader_client, env["foreign_audio"])
    assert "transcriptText" not in row, row.get("transcriptText")


def test_the_dataset_download_holder_keeps_their_own_uploads(env, downloader_client, client):
    """The control. The fix must narrow the boolean's reach to the same set ``/export/dataset``
    already puts in its manifest — own uploads plus granted owners — not to nothing at all."""
    # Under their OWN prefix: ``/media/complete`` refuses a key outside ``media/<caller id>/``.
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
    # And the same row is still withheld from a DESIGNER who has been granted nothing by them,
    # which is what proves the narrowing is per-uploader rather than per-role.
    assert "objectKey" not in _media_row(client, own_id)
