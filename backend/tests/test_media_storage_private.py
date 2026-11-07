from __future__ import annotations

import re
from datetime import UTC, datetime
from pathlib import Path
from types import SimpleNamespace
from urllib.parse import parse_qs, urlsplit

import pytest
from fastapi import HTTPException
from pydantic import ValidationError

from app.api.routes import app_release, data_browser, media
from app.core import config
from app.questionnaire import recordings
from app.questionnaire.schemas import PresignRequest as V2PresignRequest
from app.schemas.media import (
    MultipartCompleteRequest,
    MultipartCreateRequest,
    MultipartPresignPartsRequest,
    PresignRequest,
)
from app.services import record_fields, records, s3
from app.services.datareport import portal

TERRAFORM = Path(__file__).resolve().parents[2] / "infra" / "terraform" / "main.tf"
GIB = 1024 * 1024 * 1024
UPLOADER = "user-who-uploaded-the-take"
STRANGER = "user-with-no-grant-at-all"
OBJECT_KEY = f"media/{UPLOADER}/0a1b2c3d4e5f/Artisan-Photo-1-07112026.jpg"
PERMANENT = f"https://designrepo-media.s3.dualstack.ap-south-1.amazonaws.com/{OBJECT_KEY}"


def _storage(monkeypatch, ceiling: int = GIB) -> SimpleNamespace:
    settings = SimpleNamespace(
        aws_s3_bucket="designrepo-media-test",
        aws_region="ap-south-1",
        aws_s3_endpoint=None,
        aws_s3_public_base_url="https://designrepo-media.s3.ap-south-1.amazonaws.com",
        aws_access_key_id="AKIAEXAMPLEEXAMPLE00",
        aws_secret_access_key="example-secret-not-real-0000000000000000",
        aws_s3_sse_algorithm="AES256",
        media_upload_max_bytes=ceiling,
        media_presigned_reads=True,
        media_presigned_read_ttl_seconds=900,
    )
    monkeypatch.setattr(s3, "get_settings", lambda: settings)
    monkeypatch.setattr(media, "get_settings", lambda: settings)
    return settings


def _signed_headers(url: str) -> list[str]:
    query = parse_qs(urlsplit(url).query)
    return (query.get("X-Amz-SignedHeaders") or [""])[0].split(";")


def _user(user_id: str = UPLOADER, role: str = "RESEARCHER") -> SimpleNamespace:
    return SimpleNamespace(id=user_id, role=role, canDownloadDataset=False)


def _no_permanent_url(payload: object) -> None:
    assert PERMANENT not in repr(payload)
    assert "s3.ap-south-1.amazonaws.com/media/" not in repr(payload)


def test_signed_media_reads_are_on_by_default():
    assert config.Settings.model_fields["media_presigned_reads"].default is True


async def test_presign_returns_no_permanent_url_and_binds_the_byte_count(monkeypatch):
    _storage(monkeypatch)
    payload = PresignRequest(filename="loom.jpg", mimeType="image/jpeg", mediaType="IMAGE", sizeBytes=123_456)

    out = await media.presign_media_upload(payload, _user())

    assert out["publicUrl"] is None
    assert "content-length" in _signed_headers(out["uploadUrl"])


async def test_multipart_create_and_complete_return_no_permanent_url(monkeypatch):
    _storage(monkeypatch)
    monkeypatch.setattr(media, "create_multipart_upload", lambda key, mime: "upload-1")
    monkeypatch.setattr(media, "complete_multipart_upload", lambda key, upload_id, parts: None)

    created = await media.create_multipart(
        MultipartCreateRequest(filename="walk.mp4", mimeType="video/mp4", mediaType="VIDEO", sizeBytes=40 * 1024 * 1024),
        _user(),
    )
    done = await media.complete_multipart(
        MultipartCompleteRequest(
            objectKey=created["objectKey"], uploadId="upload-1", parts=[{"partNumber": 1, "etag": "e1"}]
        ),
        _user(),
    )

    assert created["publicUrl"] is None
    assert done["publicUrl"] is None


@pytest.mark.parametrize(
    "parts",
    [list(range(1, 20_001)), [0], [1, 1], [10_001]],
)
def test_presign_parts_refuses_unbounded_or_invalid_part_lists(parts):
    with pytest.raises(ValidationError):
        MultipartPresignPartsRequest(objectKey=f"media/{UPLOADER}/x/a.mp4", uploadId="u", partNumbers=parts)


async def test_presign_parts_refuses_parts_beyond_the_upload_ceiling(monkeypatch):
    _storage(monkeypatch, ceiling=64 * 1024 * 1024)
    signed: list[int] = []
    monkeypatch.setattr(media, "presign_upload_part", lambda key, upload_id, n: signed.append(n) or f"https://s/{n}")
    payload = MultipartPresignPartsRequest.model_construct(
        objectKey=f"media/{UPLOADER}/x/a.mp4", uploadId="u", partNumbers=list(range(1, 501))
    )

    with pytest.raises(HTTPException) as refused:
        await media.presign_multipart_parts(payload, _user())

    assert refused.value.status_code in (413, 422)
    assert signed == []


async def test_latest_app_release_url_is_signed_not_permanent(monkeypatch):
    release = SimpleNamespace(
        id="rel-1",
        versionCode=40,
        versionName="0.0.17",
        objectKey=f"media/{UPLOADER}/abc/design-workshop-v40.apk",
        url=PERMANENT,
        notes=None,
        sizeBytes=10,
        publishedAt=datetime(2026, 11, 7, tzinfo=UTC),
    )

    async def latest():
        return release

    monkeypatch.setattr(app_release, "_latest_release", latest)
    monkeypatch.setattr(app_release, "public_url_for_key", lambda key: PERMANENT)
    monkeypatch.setattr(
        app_release,
        "presign_get_url",
        lambda key, **kw: f"https://signed.example.test/{key}?X-Amz-Signature=abc&X-Amz-Expires={kw.get('expires_in', 900)}",
    )

    out = await app_release.latest_release(_user())

    assert "X-Amz-Signature=" in out["url"]
    _no_permanent_url(out)


def _media_row(**extra) -> SimpleNamespace:
    return SimpleNamespace(
        id="media-77",
        originalFilename="loom.jpg",
        mediaType="IMAGE",
        mimeType="image/jpeg",
        sizeBytes=2048,
        objectKey=OBJECT_KEY,
        url=PERMANENT,
        transcriptText=None,
        uploadedBy=SimpleNamespace(name="A Researcher"),
        createdAt=datetime(2026, 11, 7, tzinfo=UTC),
        recordedAt=None,
        **extra,
    )


def test_data_tree_media_entries_do_not_carry_the_permanent_url(monkeypatch):
    monkeypatch.setattr(data_browser, "folder_order", lambda rows: rows)
    monkeypatch.setattr(data_browser, "unique_display_filename", lambda m, used, **kw: "Artisan-Photo-1.jpg")
    monkeypatch.setattr(data_browser, "transcript_filename", lambda m, **kw: "Artisan-Photo-1.txt")

    entries = data_browser._media_entries("Artisans/Rekha", [_media_row()])

    assert entries[0]["url"] == "/api/data/media/media-77/download"
    _no_permanent_url([{k: v for k, v in e.items() if not k.startswith("_")} for e in entries])


def test_report_workbook_media_cell_is_a_signed_in_portal_link(monkeypatch):
    monkeypatch.setattr(portal, "web_base", lambda: "https://portal.example.test")

    facts = data_browser._media_facts(_media_row())

    assert facts["url"] == "https://portal.example.test/data/go/media/media-77/download"


def test_record_csv_media_column_is_a_signed_in_portal_link(monkeypatch):
    monkeypatch.setattr(portal, "web_base", lambda: "https://portal.example.test")

    *_, urls = record_fields.media_row([_media_row()])

    assert urls == "https://portal.example.test/data/go/media/media-77/download"


def test_terraform_media_prefix_is_not_publicly_readable():
    text = TERRAFORM.read_text(encoding="utf-8")
    assert "PublicReadMedia" not in text
    assert 'aws_s3_bucket_policy" "media_public_read"' not in text
    assert not re.search(r'Principal\s*=\s*"\*"', text)
    block = re.search(r'resource "aws_s3_bucket_public_access_block" "media" \{(.*?)\n\}', text, re.DOTALL)
    assert block, "no public access block for the media bucket"
    for setting in ("block_public_acls", "block_public_policy", "ignore_public_acls", "restrict_public_buckets"):
        assert re.search(setting + r"\s*=\s*true", block.group(1)), setting


def test_terraform_aborts_abandoned_media_multipart_uploads():
    text = TERRAFORM.read_text(encoding="utf-8")
    lifecycle = re.search(
        r'resource "aws_s3_bucket_lifecycle_configuration" "media" \{(.*?)\n\}', text, re.DOTALL
    )
    assert lifecycle
    rules = re.split(r"\n  rule \{", lifecycle.group(1))
    media_rules = [r for r in rules if re.search(r'prefix\s*=\s*"media/"', r)]
    assert any("abort_incomplete_multipart_upload" in r for r in media_rules)


def test_cleaned_audio_key_is_withheld_with_the_original(monkeypatch):
    monkeypatch.setattr(
        config,
        "get_settings",
        lambda: SimpleNamespace(media_presigned_reads=False, media_presigned_read_ttl_seconds=900),
    )
    node = {
        "id": "media-9",
        "mimeType": "audio/mp4",
        "uploadedById": UPLOADER,
        "objectKey": OBJECT_KEY,
        "url": PERMANENT,
        "cleanedObjectKey": f"media/{UPLOADER}/ffff/Interview-Audio-cleaned.m4a",
        "cleanedMimeType": "audio/mp4",
    }

    out = records.public_encode(node, _user(STRANGER))

    assert "objectKey" not in out
    assert "cleanedObjectKey" not in out


XML_TYPES = [
    "application/xml",
    "text/xml",
    "text/xsl",
    "image/svg+xml",
    "application/atom+xml",
    "application/rss+xml",
    "application/xslt+xml",
    "application/mathml+xml",
    "image/x+xml",
    "application/vnd.example+xml",
    "TEXT/XML; charset=utf-8",
]


@pytest.mark.parametrize("mime", XML_TYPES)
def test_xml_document_types_cannot_be_uploaded(mime):
    assert media.is_uploadable_mime(mime) is False


@pytest.mark.parametrize("mime", XML_TYPES)
def test_xml_document_types_are_never_served_inline(mime):
    assert media.signed_read_headers(mime)[1] == "attachment"


class _IntentDb:
    def __init__(self) -> None:
        self.inserted: dict | None = None

    async def execute_raw(self, sql: str, *args):
        if sql.startswith('INSERT INTO "MediaUploadIntent"'):
            self.inserted = {
                "id": args[0],
                "objectKey": args[1],
                "clientClipKey": args[2],
                "sessionId": args[3],
                "uploaderId": args[4],
                "sizeBytes": args[5],
                "contentType": args[6],
                "contentSha256": args[7],
                "partSize": args[8],
                "closedAt": None,
                "multipartUploadId": None,
            }
        return 1


def _v2_world(monkeypatch, db: _IntentDb) -> None:
    async def intent_row(client, key):
        return db.inserted

    async def session_for_upload(client, session_id, user):
        return {"id": session_id, "questionnaireId": "q-1", "workshopId": None}

    async def mint(client, payload, user, session):
        return f"media/{user.id}/abcd/Interview-Take-1.{payload.extension}"

    monkeypatch.setattr(recordings, "_intent_row", intent_row)
    monkeypatch.setattr(recordings, "_session_for_upload", session_for_upload)
    monkeypatch.setattr(recordings, "_mint_object_key", mint)


def _v2_payload(**overrides) -> V2PresignRequest:
    body = {
        "linkedRecordType": "questionnaire_session",
        "sessionId": "session-1",
        "clientClipKey": "clip-1",
        "sizeBytes": 4096,
        "contentType": "audio/mp4",
        "extension": "m4a",
    }
    body.update(overrides)
    return V2PresignRequest(**body)


@pytest.mark.parametrize(
    ("content_type", "extension"),
    [("text/html", "html"), ("application/xhtml+xml", "xhtml"), ("image/svg+xml", "svg"), ("text/xml", "xml")],
)
async def test_v2_presign_refuses_a_browser_executable_content_type(monkeypatch, content_type, extension):
    _storage(monkeypatch)
    db = _IntentDb()
    _v2_world(monkeypatch, db)

    with pytest.raises(HTTPException) as refused:
        await recordings.presign(_v2_payload(contentType=content_type, extension=extension), _user(), client=db)

    assert refused.value.status_code == 422
    assert db.inserted is None


async def test_v2_presign_refuses_a_take_over_the_upload_ceiling(monkeypatch):
    _storage(monkeypatch, ceiling=1024 * 1024)
    db = _IntentDb()
    _v2_world(monkeypatch, db)

    with pytest.raises(HTTPException) as refused:
        await recordings.presign(_v2_payload(sizeBytes=5 * 1024 * 1024), _user(), client=db)

    assert refused.value.status_code in (413, 422)
    assert db.inserted is None


async def test_v2_presign_binds_the_byte_count(monkeypatch):
    _storage(monkeypatch)
    db = _IntentDb()
    _v2_world(monkeypatch, db)

    out = await recordings.presign(_v2_payload(), _user(), client=db)

    assert "content-length" in _signed_headers(out["url"])

