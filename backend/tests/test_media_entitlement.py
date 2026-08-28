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
from datetime import UTC, datetime

import pytest

import app.services.stage_definitions  # noqa: F401  - installs the registry
from app.core.db import db
from app.core.security import create_access_token, hash_password

# THE MODULE RATHER THAN ITS MEMBERS, and only section 5 needs it. Two of the tests there replace
# ``media_url_owners`` and ``_design_workshop_media_ids`` with stubs to observe that the second query
# is NOT made; ``media_url_scope`` resolves both by name out of this module's globals at call time,
# so ``monkeypatch.setattr(records, ...)`` reaches them and a ``from ... import`` would not.
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

# The photographs section 5 is about, and their controls. Each of these rows is created with a real
# ``url`` — ``_media`` leaves that column NULL, which every test above is content with because it
# asserts on ``objectKey`` — so that "no url" down there means the gate removed a fetchable string
# rather than that the fixture never wrote one. ``POST /media/complete`` fills the column from
# ``public_url_for_key(objectKey)`` on every real upload, so a populated one is the normal state.
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
        # THE FOUR PHOTOGRAPHS THE URL GATE IS DECIDED ON, and the whole design of the set is that
        # ALL FOUR ARE UPLOADED BY THE DESIGNER. The colleague uploads none of them and holds no
        # DataAccessGrant from anybody, so the uploader set ``media_url_owners`` computes for them is
        # exactly ``{colleague.id}`` in all four cases and cannot be what decides any of these
        # answers. What differs is one thing per row:
        #   team_photo  — tagged to the workshop the colleague is a viewer of      → url travels
        #   other_photo — tagged to a workshop the colleague holds no viewer row on → withheld
        #   plain_photo — the SAME uploader, no tag columns at all                 → withheld
        #   dead_photo  — tagged to a workshop the colleague IS on, soft-deleted   → withheld
        # Holding the uploader constant is what makes these a test of the FILE'S TAG rather than of
        # the uploader set; see the banner over section 5 for why that distinction is the fix.
        team_photo = await _media(designer.id, stamp, "IMAGE", "team-loom.jpg",
                                  url=TEAM_PHOTO_URL,
                                  linkedRecordType="designWorkshop",
                                  linkedRecordId=team_workshop.id)
        # A SECOND REAL WORKSHOP OF THE DESIGNER'S, with no viewer row for the colleague. Created
        # here rather than through ``_workshop`` because that helper opens a workshop as the ADMIN
        # and this one has to be filed under the same uploader as ``team_photo`` to be a control.
        other_workshop = await db.designworkshop.create(data={
            "title": f"Bamboo fortnight {stamp}", "createdById": designer.id,
        })
        other_photo = await _media(designer.id, stamp, "IMAGE", "other-loom.jpg",
                                   url=OTHER_PHOTO_URL,
                                   linkedRecordType="designWorkshop",
                                   linkedRecordId=other_workshop.id)
        plain_photo = await _media(designer.id, stamp, "IMAGE", "plain-loom.jpg",
                                   url=PLAIN_PHOTO_URL)
        # SOFT-DELETED, AND THE VIEWER ROW IS DELIBERATELY STILL WRITTEN. Deleting a workshop does
        # not delete the grants on it, so ``deletedAt`` is the ONLY difference between this row and
        # ``team_photo`` — which is what makes 5b's
        # ``test_a_soft_deleted_workshop_stops_conferring_the_url`` a test of
        # ``_design_workshop_media_ids``'s ``deletedAt: None`` clause and not of the grant. Named
        # rather than pointed at ("the last test in 5b", which it is not: one more follows it), for
        # the same reason nothing in this file cites a line number. Without that clause a grant on a
        # workshop nobody can open any more keeps handing out its files.
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
            "team_photo": team_photo.id,
            "other_photo": other_photo.id,
            "plain_photo": plain_photo.id,
            "dead_photo": dead_photo.id,
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
# 3b. The transcript columns, planted through the same door
# --------------------------------------------------------------------------------------
#
# THE SAME DEFECT AS ``url``, ONE FIELD OVER, AND WORSE. ``MediaCompleteRequest`` declares
# ``transcriptText``, ``transcriptSummary``, ``transcriptStatus`` and ``transcriptError``, and
# ``complete_media_upload`` dumped all four straight into ``db.mediafile.create``. The three writers
# that are supposed to exist all sit behind a gate — ``services/media_queue`` (reached only through
# ``enqueue_media_processing_jobs``, the choke point the artisan's consent gate is in),
# ``POST /media/{id}/transcript`` (a person typing the words), and ``services/dictation_consent``'s
# revocation sweep (which only ever moves rows to FAILED); they are enumerated with the count and the
# grep that found them at ``media.SERVER_WRITTEN_TRANSCRIPT_FIELDS``. A caller who posted the words
# WITH the upload walked past all three, and section 4 below is what a planted transcript then
# reaches: the widest reads in the application, plus every co-designer on the workshop it is under.
#
# The pair mirrors the ``url`` pair above deliberately: IGNORED, and still ACCEPTED. Neither shipped
# client sends any of the four today, but ``APIModel`` forbids extra keys, so deleting them from the
# schema would 422 any build that does — and Android's ``saveOrQueue`` does not queue a 4xx, so that
# 422 loses the recording rather than retrying it.

PLANTED_TRANSCRIPT = "**Interviewer:** PLANTED-BY-THE-CALLER did you agree to this?\n"


def _every_string_in(row: dict) -> str:
    """Every string anywhere in a media row, so a planted transcript cannot hide in a key this test
    did not think to name — ``extraMetadata``, a nested processing job, the caption."""
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
    """The four transcript columns are the server's to write. ``/complete`` is not one of its
    writers, so a body carrying them creates a row with all four still empty."""
    created = _complete_audio_with_transcript(client, env["designer_id"])

    # Read back through the detail route as the UPLOADER, who is entitled to their own transcript
    # (``test_the_uploaders_own_transcript_still_travels``) — so an absent one here is the column
    # being empty and not the entitlement gate hiding a populated column.
    row = _media_row(client, created["id"])
    assert not (row.get("transcriptText") or ""), row.get("transcriptText")
    assert not (row.get("transcriptSummary") or ""), row.get("transcriptSummary")
    assert "PLANTED-BY-THE-CALLER" not in _every_string_in(row)

    # And the queue's own bookkeeping is untouched: no job was asked for, so nothing claims one ran.
    assert (row.get("transcriptStatus") or None) != "COMPLETED", row.get("transcriptStatus")


def test_the_transcript_fields_are_still_ACCEPTED_so_installed_clients_keep_uploading(env, client):
    """The other half of the pair. Dropping the keys from the schema would 422 any build still
    sending them, and a 422 on Android is an upload that is never retried."""
    created = _complete_audio_with_transcript(client, env["designer_id"])
    assert created["id"], "the row was created, not refused"
    assert created["mediaType"] == "AUDIO"


def test_a_whole_shipped_client_body_still_succeeds_and_the_server_values_win(env, client):
    """THE WHOLE BODY AT ONCE, because the two pairs above each test one field in isolation and no
    shipped client ever sends one field in isolation.

    The keys below are the union of what the two clients actually put on the wire — the web's
    ``completeUpload`` body (``frontend/lib/media.ts``, which sends ``url`` AND ``checksum``) plus the
    four transcript keys an older build merged in from ``transcribeMediaFile``, which returns exactly
    ``{transcriptText, transcriptStatus, transcriptError}``. That combination is the one an installed
    build can produce and no test covered it: ``APIModel`` sets ``extra="forbid"``, so a body is
    accepted or refused AS A WHOLE, and five ignored keys together is a different assertion from five
    ignored keys apart.

    IT MUST BE A 2xx AND NOT A 422. On Android ``saveOrQueue`` does not queue a 4xx, so a refused body
    is a recording deleted rather than retried — a request-shape change a deployed build cannot
    satisfy is a data-loss defect, not a tidy-up.

    AND THE SERVER'S OWN VALUES MUST BE WHAT IS STORED: the URL derived from the object key rather
    than the caller's, and four empty transcript columns for the consent gate's writers to fill. A
    202-shaped "accepted" that quietly kept the caller's strings would satisfy the first half of this
    test and defeat the point of the change.
    """
    key = f"media/{env['designer_id']}/{uuid.uuid4().hex}/whole-body.webm"
    response = client.post("/api/media/complete", json={
        # What the server needs.
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
        # Accepted and ignored: the caller's URL never wins.
        "url": HOSTILE_URL,
        # Accepted and KEPT: a checksum is a client-computed fact with no server counterpart.
        "checksum": "sha256:0123456789abcdef",
        # Accepted and ignored: the four the consent gate owns.
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
    # Read back as the UPLOADER, who is entitled to their own transcript, so an empty column here is
    # the column and not the entitlement gate hiding a populated one.
    assert not (row.get("transcriptText") or ""), row.get("transcriptText")
    assert not (row.get("transcriptSummary") or ""), row.get("transcriptSummary")
    assert (row.get("transcriptStatus") or None) != "COMPLETED", row.get("transcriptStatus")
    assert "PLANTED-BY-THE-CALLER" not in _every_string_in(row)
    assert row["url"] != HOSTILE_URL
    # The half that must NOT be dropped: everything the caller is the authority on still arrives.
    assert row["caption"] == "Interview with the master weaver"
    assert row["checksum"] == "sha256:0123456789abcdef"


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


# --------------------------------------------------------------------------------------
# 5. The URL gate and the co-designer: the sixth surface, and the only one that disagreed
# --------------------------------------------------------------------------------------
#
# THE REFUSAL WAS THE DEFECT THIS TIME. A co-designer could already take a workshop's media BYTES on
# FIVE surfaces — ``GET /design-workshops/{id}/transcripts`` (pinned by
# ``test_a_granted_co_designer_is_shown_the_workshops_own_recordings`` above), the AI layer's
# ``_readable_media_ids``, ``/export``, ``/data``, and the images in the report they themselves sign
# — because ``records.owned_or_granted_where(owner_field="uploadedById")`` carries an arm keyed on
# the FILE'S TAG rather than on its uploader (``_design_workshop_media_branches``).
# ``media_url_owners`` was the one gate still keyed on uploader identity, so ``GET /media`` withheld
# the ``url`` of a photograph the same account could obtain by pressing Generate on the same
# workshop. Two live rules over one set of bytes, which is the shape of every defect in this file;
# what is different here is that the permissive rule was the CORRECT one, so the symptom was a
# refusal — and a refusal on this surface is INVISIBLE, because a missing ``url`` renders as a broken
# image rather than as a policy. ``tests/test_record_media_urls`` pins that same silent failure for
# products and tools and says so at length.
#
# THE FIX WIDENS THE TEST, NOT THE SET, and everything below is arranged around that one sentence.
# ``media_urls`` is a set of UPLOADER ids and ``public_encode`` applies it at ``/search``,
# ``/products``, ``/tools``, ``/processes`` and the consolidated questionnaire — not one of which has
# a workshop in it. Folding the uploading colleague's id into that set would say "this account may
# take that uploader's data EVERYWHERE", which is a far larger claim than "may take the files of the
# workshop we run together", and it would be invisible at every one of those call sites. So the
# second half travels as its own argument, ``media_workshops``, and ``_redact_sensitive`` asks the
# narrower question against the file's own two tag columns.
# ``test_the_same_uploaders_untagged_file_is_still_withheld`` is the test that tells those two
# fixes apart: it is the one that goes red the day this is rewritten as a wider set, and it is the
# reason that claim is written twice, once against the predicate and once through the route.
#
# NOTHING IN 5a NEEDS A DATABASE, and it is in two halves. The tests over ``_redact_sensitive`` hand
# it a dict and read the keys back, which is the honest level for a per-node predicate: nothing about
# it depends on which rows a query returned. The tests over ``media_url_scope`` stub both of its
# reads, because what they pin is WHICH QUERIES IT MAKES rather than what those queries answer — and
# a stub is the only way to observe a query that was NOT made. (This banner used to open "THE NINE
# TESTS IN 5a", which was true on the day it was written and described only the first six of them.)
# They all still sit inside this module's skip, because ``pytestmark`` is per-module and this is the
# module where this gate is argued.

#: A workshop id, an uploader who is NOT the viewer, and a fetchable string to withhold. Plain
#: constants rather than fixture rows: none of 5a touches Postgres and none of it needs a real cuid.
UNIT_WORKSHOP = "dw-the-one-we-run-together"
UNIT_UPLOADER = "user-the-colleague-who-uploaded-it"
UNIT_VIEWER = "user-the-co-designer-reading-it"
UNIT_URL = "https://cdn.example.test/media/pit-loom.jpg"


def _encoded_media(**extra) -> dict:
    """One MediaFile as ``public_encode`` hands it to ``_redact_sensitive``: uploaded by SOMEBODY
    ELSE, and carrying every takeable key with a real value in it.

    Hand-built rather than loaded, because what is under test is a predicate over an ALREADY-ENCODED
    payload: the walk never sees a row, only a dict carrying the ``objectKey`` marker, its own
    ``uploadedById``, and now the two tag columns. Building it here is also what lets the tag be
    wrong in five different ways below, which no fixture row could be.
    """
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
    """The same file as a design-workshop upload arrives: both tag columns, written by the CLIENT.

    The literal ``"designWorkshop"`` is deliberate and is not ``records.MEDIA_TAG`` — this string is
    on the wire from ``WorkshopRepository.uploadDesignWorkshopMedia`` on the handset and from
    ``designWorkshopStore.ts`` in the browser, and it is already persisted on every workshop
    recording in the corpus. A test that spelled it as the constant would keep passing if the
    constant were changed, while every phone in the field kept sending the old string.
    """
    return _encoded_media(
        linkedRecordType="designWorkshop", linkedRecordId=workshop_id, **extra
    )


def _takeable(node: dict) -> set[str]:
    """Which of the bytes-bearing keys survived the walk.

    Read off ``records._MEDIA_TAKEABLE_KEYS`` rather than listed here, so a key added to that tuple
    is covered by these tests on the day it is added rather than the day somebody remembers this
    file. ``transcriptText`` was added to it exactly once, and the reason section 4 exists is that
    for months before that it was in none of the three lists.
    """
    return {key for key in records._MEDIA_TAKEABLE_KEYS if key in node}


# --------------------------------------------------------------------------------------
# 5a. The predicate, with no database in it
# --------------------------------------------------------------------------------------


def test_a_co_designer_keeps_the_bytes_of_a_file_tagged_to_their_own_workshop():
    """THE FIX. A photograph the reader did not upload, filed under the workshop they are a viewer of.

    The uploader is not in ``media_urls`` and never becomes so — that set is the viewer's own id, as
    it is for any account holding no DataAccessGrant. What admits the file is its own pair of tag
    columns naming a workshop in ``media_workshops``.

    ALL FIVE KEYS MOVE TOGETHER, and the assertion is written as the whole tuple rather than as
    ``"url" in node`` for that reason: ``url``, ``publicUrl`` and ``objectKey`` are three spellings
    of the same download (``public_url_for_key`` is the CDN host plus the key), and the transcript IS
    the recording, in text. A fix that handed back the URL and kept withholding the transcript would
    leave the co-designer able to fetch the .m4a and unable to read what is in it, on a screen whose
    job is to show them what they are about to send to a ministry.
    """
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
    """The control that makes the clause a SCOPE rather than a role.

    Same uploader, same tag, a workshop id the viewer's own ``media_workshops`` does not contain.
    Every design-workshop upload in the repository carries this tag, so a clause that tested only
    ``linkedRecordType == "designWorkshop"`` would hand every workshop's photographs and interviews
    to every designer in the country — which is the version of this fix that must never ship, and is
    exactly what ``test_a_designer_with_no_grant_is_still_refused_the_same_recording`` refuses one
    surface earlier.
    """
    node = records._redact_sensitive(
        _tagged_media("dw-somebody-elses-fortnight"),
        viewer_id=UNIT_VIEWER,
        unmasked=False,
        media_urls={UNIT_VIEWER},
        media_workshops=frozenset({UNIT_WORKSHOP}),
    )

    assert _takeable(node) == set()


def test_the_same_uploaders_untagged_file_is_still_withheld():
    """THE MOST IMPORTANT TEST IN THIS SECTION: it is what proves the TEST widened and not the SET.

    The same uploader as the file two tests above, whose workshop photograph this account may now
    take — and an ordinary upload of theirs with no tag columns on it: an artisan's portrait attached
    to a product, the photograph of an Aadhaar card, a recording made on somebody else's fieldwork.
    None of that is in the workshop the two of them run together, and none of it may travel.

    THIS IS THE TEST THAT FAILS IF SOMEBODY "SIMPLIFIES" THE FIX by adding the co-designer's
    colleagues to ``media_urls``. That set is applied by ``public_encode`` at ``/search``,
    ``/products``, ``/tools``, ``/processes`` and the consolidated questionnaire, none of which has a
    workshop in it and none of which would show any sign of having widened: one viewer grant on one
    workshop would quietly become a standing download entitlement over that uploader's entire
    contribution to the repository, on five surfaces that never mention design workshops at all.
    """
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
    """FAIL-CLOSED BY CONSTRUCTION: only BOTH columns, both right, open the new arm.

    Every one of these five is a shape that really occurs. Both columns are nullable and
    caller-supplied; ``linkedRecordType`` alone is what a half-migrated client writes; the bare id is
    what an attacker would try after reading this file; and ``"workshop"`` is a DIFFERENT model
    (``data_browser``'s tag map lists artisan, product, process, processstep, tool, workshop,
    questionnaire), so an id under that tag names a row in another table entirely. Matching on the
    id alone would let any of those confer a design workshop's entitlement.

    The node still carries the ``objectKey`` marker in all five, which is the case the third arm has
    to fall through cleanly: it drops to the uploader test, the uploader is not the viewer, and the
    keys go. A caller that has not thought about ``media_workshops`` at all withholds exactly as much
    as it did before this argument existed.
    """
    node = records._redact_sensitive(
        _encoded_media(**tag_columns),
        viewer_id=UNIT_VIEWER,
        unmasked=False,
        media_urls={UNIT_VIEWER},
        media_workshops=frozenset({UNIT_WORKSHOP}),
    )

    assert _takeable(node) == set()


def test_public_encode_carries_the_workshops_only_when_the_call_site_passes_them():
    """PASS-THROUGH IS EXPLICIT AT EVERY CALL SITE, AND OMISSION IS THE SAFE ANSWER.

    Both halves matter and neither implies the other. Without the first, ``public_encode`` could
    accept ``media_workshops`` and drop it on the floor: every test above would still pass, because
    they call ``_redact_sensitive`` directly, and the route — which calls ``public_encode`` — would
    go on refusing the co-designer. Without the second, a route that has never heard of design
    workshops could start handing out their files by accident.

    The default is the whole discipline of this module's encoder: ``media_urls`` already has a
    sentinel distinct from ``None`` precisely because ``None`` means "allow everything", so "the
    caller did not say" and "the caller said yes to everything" cannot be spelled the same way. The
    transcript banner over ``records._MEDIA_URL_KEYS`` records what the permissive default cost the
    last time one existed here: the verbatim text of every artisan interview in the repository,
    served to the authentication floor, on the widest read in the application.
    """
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
    """The walk is recursive and the new argument has to be carried down BOTH of its branches.

    Almost nothing that carries media is a bare media node: a workshop arrives with a ``media`` LIST
    of them, a stage read carries them under a field key, ``processes._hydrate`` hangs them off each
    step. Dropping the argument on either recursive call would give a gate that holds on
    ``GET /media/{id}`` and fails on every response that embeds a file — the same split between one
    route and the rest that ``test_record_media_urls`` was written for.

    Both leaves are in the one payload on purpose: the withheld one proves the scope is still being
    TESTED per node down there, rather than the list as a whole being waved through once its parent
    matched.
    """
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
    """``(ALL_MEDIA_URLS, frozenset())``, and the second lookup is never made.

    ``ALL_MEDIA_URLS`` is ``None`` and means "every URL travels", so which workshops a professor is on
    changes no answer at all — asking would be a round trip whose result is discarded, on every
    encode of every media-bearing response. The empty ``frozenset`` beside it is not a refusal here;
    it is the redundant half, and ``_redact_sensitive`` never reaches its arm because the uploader
    test in front of it is already satisfied for everybody.

    THE STUB IS THE ASSERTION. "No query" cannot be observed from the return value — a professor and
    an account on no workshops both come back with an empty set of workshop ids — so
    ``_design_workshop_media_ids`` is replaced with one that records being called and returns an id
    that would be visible if it were ever consulted. The rank test in ``media_url_owners`` runs
    first and never touches Postgres, so this test genuinely needs none.
    """
    asked = []

    async def _record(user_id: str) -> list[str]:
        asked.append(user_id)
        return ["dw-a-professor-was-asked-about-this"]

    monkeypatch.setattr(records, "_design_workshop_media_ids", _record)

    scope = await records.media_url_scope({"id": "user-professor", "role": "PROFESSOR"})

    assert scope == (records.ALL_MEDIA_URLS, frozenset())
    assert asked == [], "a professor's URL scope must not cost a workshop query"


async def test_the_scope_is_composed_from_the_uploader_set_rather_than_replacing_it(monkeypatch):
    """``media_url_scope`` CALLS ``media_url_owners`` and puts the workshop half beside its answer.

    Which is what keeps the call sites that want only the uploader half paying for exactly one query
    — eight of them as of 2026-08-27, check `grep -rn 'media_url_owners(' backend/app`, which also
    prints ``records.py``'s own definition and the one internal call from ``media_url_scope`` — and,
    the reason that matters more, keeps ONE spelling of "whose uploads may travel". Two spellings of
    a permission question is the drift that produced the defect this section closes: the download
    surfaces admitted a co-designer through ``_design_workshop_media_ids`` and the URL gate answered
    a different question a few hundred lines away.

    THE NUMBER WENT DOWN, WHICH IS WHY IT IS DATED. It was ten before this change and this docstring
    first said eleven, a count nobody could re-derive because none of the three of us who read it
    ever ran the grep. ``media.py`` and ``search.py`` are the two that left the list: they now ask
    for the pair, and every count of this shape in the repository is now written with the date it
    was true and the command that re-derives it, because that is the only form that survives.

    Both halves are stubbed because both are database round trips and neither one's SQL is what this
    test is about. What it pins is that the two answers arrive in the right slots, that the grant
    half is NOT quietly widened on the way past, and that the workshop half is asked about the
    VIEWER rather than about some id lifted off the payload.
    """
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
    """The degenerate caller, which every gate in this file has to answer safely.

    ``public_encode`` may be handed anything a route calls a viewer, and an object with no ``id`` is
    what an unauthenticated or half-built one looks like. The uploader half is an empty set — nobody
    — and the workshop half must not be looked up at all, because ``_design_workshop_media_ids`` with
    a falsy id is a query for whatever the empty string is a viewer of. Neither half touches
    Postgres, so this test does not either.
    """
    asked = []

    async def _record(user_id: str) -> list[str]:
        asked.append(user_id)
        return []

    monkeypatch.setattr(records, "_design_workshop_media_ids", _record)

    assert await records.media_url_scope({"role": "DESIGNER"}) == (set(), frozenset())
    assert asked == []


# --------------------------------------------------------------------------------------
# 5b. The same answers, through the route that actually serves them
# --------------------------------------------------------------------------------------
#
# The predicate is only half of the fix. ``GET /media/{id}`` has to ASK for the workshop half, and
# ``public_encode``'s default is deliberately the cheap one that does not — so a correct predicate
# with an un-updated call site is a fix that changes nothing a designer can see. These four rows
# differ from one another in exactly one thing each (the tag, the workshop it names, or that
# workshop's ``deletedAt``) and every one of them was uploaded by the DESIGNER, never by the account
# reading them.
#
# THAT ONE UPLOADER IS ALSO WHAT MAKES 5b NOTICE THE WRONG FIX. Widen the uploader SET instead — put
# the designer's id into the colleague's ``media_urls`` because the two of them share a workshop —
# and the URL comes back on ``other_photo``, ``plain_photo`` and ``dead_photo`` together, because the
# set is keyed on the uploader and all three have the same one. Three reds, not one. But only
# ``plain_photo`` is a red nobody can explain away by re-reading the workshop scope, because it has
# no workshop on it to scope: that is
# ``test_the_uploaders_untagged_photograph_is_still_withheld_through_the_route``, the route-level
# twin of the unit test named in the banner over section 5, and the reason both exist.


def test_a_co_designer_is_handed_the_url_of_the_workshops_own_photograph(env, colleague_client):
    """THE DEFECT, AT THE SURFACE IT WAS REPORTED ON. Before the fix this row came back with the
    ``url``, ``publicUrl`` and ``objectKey`` stripped, for an account that could put the very same
    photograph into a .docx by opening the workshop and pressing Generate."""
    row = _media_row(colleague_client, env["team_photo"])

    assert row["id"] == env["team_photo"]
    assert row["url"] == TEAM_PHOTO_URL
    assert "objectKey" in row, "the key travels with the URL; one is the other"


def test_the_media_route_now_agrees_with_the_transcripts_route_for_the_same_account(
    env, colleague_client
):
    """The two surfaces that contradicted each other, asked the same question about the same file.

    ``test_a_granted_co_designer_is_shown_the_workshops_own_recordings`` proves the transcripts
    endpoint lists this recording for this account. Section 4's banner records the state where
    ``GET /media/{id}`` disagreed in the OTHER direction — serving transcripts the transcripts
    endpoint refused — and closing that left this one refusing what the transcripts endpoint serves.
    Two rules over one recording, twice, in opposite directions. This is the assertion that says they
    now answer alike.
    """
    row = _media_row(colleague_client, env["team_audio"])

    assert row["transcriptText"] == TEAM_TRANSCRIPT
    assert "OUR-SHARED-WORKSHOP" in row["transcriptText"]


def test_a_photograph_of_a_workshop_this_account_is_not_on_is_refused(env, colleague_client):
    """Same uploader, same tag, a workshop the colleague holds no viewer row for. Every
    design-workshop upload in the corpus carries this tag; only the ones naming a workshop this
    account may OPEN may travel."""
    row = _media_row(colleague_client, env["other_photo"])

    assert row["id"] == env["other_photo"], "the row still travels; only the bytes are withheld"
    assert "url" not in row, row.get("url")
    assert "objectKey" not in row


def test_the_uploaders_untagged_photograph_is_still_withheld_through_the_route(
    env, colleague_client
):
    """THE ROUTE-LEVEL PROOF THAT THE TEST WIDENED AND NOT THE SET, and the pair it completes.

    This is the same designer's upload as ``team_photo``, read by the same account, in the same
    request shape — the ONLY difference is that this one carries no tag columns. If a co-designer's
    grant were ever implemented by adding that uploader to ``media_urls``, this row would come back
    with its URL.

    IT IS NOT THE ONLY RED THAT MIS-FIX PRODUCES, and pretending otherwise is how a reader talks
    themselves out of the one that matters: all four photographs share an uploader, so
    ``other_photo`` and ``dead_photo`` go red beside it. What is only true of this row is that no
    reading of the WORKSHOP scope can account for the red — this file is tagged to nothing, so a
    URL on it can have come from nowhere but the uploader set. The other two are workshop-scope
    controls as well, and a red there is ambiguous.

    5a catches one shape of the same mistake without a database: if the widening were written into
    ``media_url_scope``,
    ``test_the_scope_is_composed_from_the_uploader_set_rather_than_replacing_it`` fails on the
    ``uploaders`` assertion. It would NOT catch a widening written inside ``media_url_owners``
    itself, which that test stubs — and the route tests here are all that stands under that one.
    """
    row = _media_row(colleague_client, env["plain_photo"])

    assert "url" not in row, row.get("url")
    assert "objectKey" not in row


def test_a_soft_deleted_workshop_stops_conferring_the_url(env, colleague_client):
    """A grant on a record nobody can open any more must not keep handing out its files.

    The colleague's viewer row on this workshop is intact — deleting a workshop does not delete its
    grants — and ``deletedAt`` is the only thing separating this row from ``team_photo``. A workshop
    with ``deletedAt`` set is a 404 for everyone below admin, so an account that cannot reach the
    workshop, its stages, its report or its transcripts must not still be able to fetch its
    photographs off ``GET /media``, which is the one surface that lists them without going through
    the workshop at all.
    """
    row = _media_row(colleague_client, env["dead_photo"])

    assert "url" not in row, row.get("url")
    assert "objectKey" not in row


def test_a_designer_not_on_that_workshop_is_refused_the_same_photograph(env, stranger_client):
    """The control on the whole section, and the mirror of
    ``test_a_designer_with_no_grant_is_still_refused_the_same_recording`` one surface over: the same
    file, the same tag, an account holding no viewer row on the workshop the file names.

    "NOT ON THAT WORKSHOP" RATHER THAN "ON NO WORKSHOP", which is what this was called and was not
    true: ``test_a_designer_with_no_grant_is_still_refused_the_same_recording`` in section 2 opens a
    workshop through ``_workshop(env, "stranger_id")`` and grants this very account a viewer row on
    it, so by the time the module reaches here the stranger is on a workshop of their own and
    ``_design_workshop_media_ids`` answers with it rather than with nothing. That makes this a
    better test than the old name claimed — the scope is being asked PER WORKSHOP, not "does this
    account hold any grant at all" — and the assertion does not depend on the ordering either way,
    because ``team_workshop`` is not among whatever comes back.
    """
    row = _media_row(stranger_client, env["team_photo"])

    assert row["id"] == env["team_photo"]
    assert "url" not in row, row.get("url")
    assert "objectKey" not in row
