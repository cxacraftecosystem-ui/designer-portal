import json
from types import SimpleNamespace

import pytest

from app.core import config
from app.services import records, s3

UPLOADER = "user-the-researcher-who-recorded-it"
VIEWER = "user-the-researcher-reading-it"
STRANGER = "user-nobody-has-granted-anything-to"
OBJECT_KEY = "media/user-the-researcher-who-recorded-it/pit-loom-photo-1-030920261412.jpg"
STORED_URL = f"https://cdn.example.test/{OBJECT_KEY}"


def _encoded_media(**extra) -> dict:
    return {
        "id": "media-1",
        "originalFilename": "pit-loom.jpg",
        "mediaType": "IMAGE",
        "mimeType": "image/jpeg",
        "caption": "The pit loom, from the door",
        "uploadedById": UPLOADER,
        "objectKey": OBJECT_KEY,
        "url": STORED_URL,
        "sizeBytes": 2_402_113,
        **extra,
    }


def _viewer(user_id: str = VIEWER, role: str = "RESEARCHER"):
    return SimpleNamespace(id=user_id, role=role, canDownloadDataset=False)


class _Recorder:
    def __init__(self) -> None:
        self.calls: list[dict] = []

    def __call__(self, object_key, *, filename, mime_type, expires_in=900, disposition="attachment"):
        self.calls.append(
            {
                "object_key": object_key,
                "filename": filename,
                "mime_type": mime_type,
                "expires_in": expires_in,
                "disposition": disposition,
            }
        )
        return f"https://signed.example.test/{object_key}?X-Amz-Signature=deadbeef"


@pytest.fixture
def signer(monkeypatch) -> _Recorder:
    recorder = _Recorder()
    monkeypatch.setattr(s3, "presign_get_url", recorder)
    return recorder


@pytest.fixture
def flag(monkeypatch):
    def _set(enabled: bool, ttl: int = 900) -> None:
        monkeypatch.setattr(
            config,
            "get_settings",
            lambda: SimpleNamespace(
                media_presigned_reads=enabled, media_presigned_read_ttl_seconds=ttl
            ),
        )

    return _set



def test_with_the_flag_off_the_payload_is_what_it_was(flag, signer):
    flag(False)
    node = _encoded_media()
    before = json.dumps(node)

    out = records.public_encode(node, _viewer(user_id=UPLOADER))

    assert json.dumps(out) == before
    assert out["url"] == STORED_URL
    assert signer.calls == [], "the signer must not be reached at all while the flag is off"


def test_with_the_flag_off_a_professor_sees_exactly_what_they_saw(flag, signer):
    flag(False)
    out = records.public_encode(_encoded_media(), _viewer(user_id=VIEWER, role="PROFESSOR"))

    assert out["url"] == STORED_URL
    assert signer.calls == []


def test_a_zero_ttl_is_read_as_off(flag, signer):
    flag(True, ttl=0)
    out = records.public_encode(_encoded_media(), _viewer(user_id=UPLOADER))

    assert out["url"] == STORED_URL
    assert signer.calls == []



def test_with_the_flag_on_an_entitled_url_is_signed(flag, signer):
    flag(True, ttl=900)
    out = records.public_encode(_encoded_media(), _viewer(user_id=UPLOADER))

    assert out["url"].startswith("https://signed.example.test/")
    assert len(signer.calls) == 1
    assert signer.calls[0] == {
        "object_key": OBJECT_KEY,
        "filename": "pit-loom.jpg",
        "mime_type": "image/jpeg",
        "expires_in": 900,
        "disposition": "inline",
    }


def test_a_withheld_node_is_never_signed(flag, signer):
    flag(True)
    out = records.public_encode(_encoded_media(uploadedById=STRANGER), _viewer(user_id=VIEWER))

    for key in records._MEDIA_TAKEABLE_KEYS:
        assert key not in out, f"{key} must not survive for an unentitled caller"
    assert signer.calls == []


def test_the_design_workshop_arm_still_admits_and_now_signs(flag, signer):
    flag(True)
    node = _encoded_media(
        uploadedById=STRANGER,
        linkedRecordType="designWorkshop",
        linkedRecordId="dw-the-one-we-run-together",
    )
    out = records._redact_sensitive(
        node,
        viewer_id=VIEWER,
        unmasked=False,
        media_urls={VIEWER},
        media_workshops=frozenset({"dw-the-one-we-run-together"}),
        presign_ttl=900,
    )

    assert out["url"].startswith("https://signed.example.test/")
    assert len(signer.calls) == 1


def test_every_media_node_in_a_nested_payload_is_signed(flag, signer):
    flag(True)
    payload = {
        "id": "product-1",
        "name": "Pit-loom shawl",
        "createdById": UPLOADER,
        "media": [_encoded_media(id="m1"), _encoded_media(id="m2")],
    }
    out = records.public_encode(payload, _viewer(user_id=UPLOADER))

    assert [m["url"].startswith("https://signed.example.test/") for m in out["media"]] == [
        True,
        True,
    ]
    assert len(signer.calls) == 2


def test_a_row_with_no_stored_url_is_left_alone(flag, signer):
    flag(True)
    out = records.public_encode(_encoded_media(url=None), _viewer(user_id=UPLOADER))

    assert out["url"] is None
    assert signer.calls == []


def test_a_signing_failure_falls_back_to_the_stored_url(flag, monkeypatch):
    flag(True)

    def _explode(*args, **kwargs):
        raise RuntimeError("no credentials configured")

    monkeypatch.setattr(s3, "presign_get_url", _explode)
    out = records.public_encode(_encoded_media(), _viewer(user_id=UPLOADER))

    assert out["url"] == STORED_URL



def test_an_html_typed_row_is_signed_as_an_inert_attachment(flag, signer):
    flag(True)
    records.public_encode(_encoded_media(mimeType="text/html"), _viewer(user_id=UPLOADER))

    assert len(signer.calls) == 1
    assert signer.calls[0]["mime_type"] == "application/octet-stream"
    assert signer.calls[0]["disposition"] == "attachment"


def test_an_svg_is_signed_as_an_inert_attachment(flag, signer):
    flag(True)
    records.public_encode(_encoded_media(mimeType="image/svg+xml"), _viewer(user_id=UPLOADER))

    assert signer.calls[0]["mime_type"] == "application/octet-stream"
    assert signer.calls[0]["disposition"] == "attachment", (
        "an SVG navigated to is an HTML document with script in it, whatever the picker calls it"
    )


def test_a_jpeg_is_still_inline_and_still_its_own_type(flag, signer):
    flag(True)
    records.public_encode(_encoded_media(), _viewer(user_id=UPLOADER))

    assert signer.calls[0]["mime_type"] == "image/jpeg"
    assert signer.calls[0]["disposition"] == "inline"


def test_a_pdf_is_inline_and_a_spreadsheet_is_not(flag, signer):
    flag(True)
    records.public_encode(_encoded_media(mimeType="application/pdf"), _viewer(user_id=UPLOADER))
    records.public_encode(
        _encoded_media(
            mimeType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        ),
        _viewer(user_id=UPLOADER),
    )

    assert [call["disposition"] for call in signer.calls] == ["inline", "attachment"]


def test_a_row_with_no_stored_type_signs_as_an_attachment(flag, signer):
    flag(True)
    records.public_encode(_encoded_media(mimeType=None), _viewer(user_id=UPLOADER))

    assert signer.calls[0] == {
        "object_key": OBJECT_KEY,
        "filename": "pit-loom.jpg",
        "mime_type": "application/octet-stream",
        "expires_in": 900,
        "disposition": "attachment",
    }


def test_the_signer_and_the_upload_door_read_one_list(flag, signer):
    from app.api.routes import media as media_routes

    assert media_routes.is_uploadable_mime("image/jpeg") is True
    assert media_routes.is_uploadable_mime("text/html") is False
    for denied in media_routes._UPLOAD_MIME_DENIED:
        assert media_routes.signed_read_headers(denied) == (
            "application/octet-stream",
            "attachment",
        ), f"{denied} is refused at the door and must be inert on the way out too"



def test_the_source_row_is_untouched_by_signing(flag, signer):
    flag(True)
    row = SimpleNamespace(
        id="media-1",
        originalFilename="pit-loom.jpg",
        mimeType="image/jpeg",
        uploadedById=UPLOADER,
        objectKey=OBJECT_KEY,
        url=STORED_URL,
    )

    out = records.public_encode(row, _viewer(user_id=UPLOADER))

    assert out["url"].startswith("https://signed.example.test/")
    assert row.url == STORED_URL, "the stored column is the flip-back path; it must not be rewritten"



@pytest.fixture
def fake_storage(monkeypatch):
    monkeypatch.setattr(
        s3,
        "get_settings",
        lambda: SimpleNamespace(
            aws_s3_endpoint=None,
            aws_region="ap-south-1",
            aws_access_key_id="AKIAEXAMPLEEXAMPLE00",
            aws_secret_access_key="not-a-real-secret-and-never-was",
            aws_s3_bucket="designrepo-media-test",
        ),
    )


def _query(url: str) -> dict[str, str]:
    from urllib.parse import parse_qs, urlsplit

    return {k: v[0] for k, v in parse_qs(urlsplit(url).query).items()}


def test_a_signed_read_url_carries_a_sigv4_signature_and_an_expiry(fake_storage):
    url = s3.presign_get_url(
        OBJECT_KEY, filename="pit-loom.jpg", mime_type="image/jpeg", expires_in=900
    )
    params = _query(url)

    assert params["X-Amz-Algorithm"] == "AWS4-HMAC-SHA256"
    assert params["X-Amz-Expires"] == "900"
    assert params["X-Amz-Signature"]
    assert params["X-Amz-Credential"].startswith("AKIAEXAMPLEEXAMPLE00/")
    assert OBJECT_KEY.rsplit("/", maxsplit=1)[-1] in url


def test_the_default_disposition_is_still_attachment(fake_storage):
    url = s3.presign_get_url(
        OBJECT_KEY, filename="pit-loom.jpg", mime_type="image/jpeg", expires_in=900
    )

    assert _query(url)["response-content-disposition"] == (
        "attachment; filename=\"pit-loom.jpg\"; filename*=UTF-8''pit-loom.jpg"
    )


def test_an_inline_disposition_is_what_the_encoder_asks_for(fake_storage):
    url = s3.presign_get_url(
        OBJECT_KEY,
        filename="pit-loom.jpg",
        mime_type="image/jpeg",
        expires_in=900,
        disposition="inline",
    )
    params = _query(url)

    assert params["response-content-disposition"] == (
        "inline; filename=\"pit-loom.jpg\"; filename*=UTF-8''pit-loom.jpg"
    )
    assert params["response-content-type"] == "image/jpeg"


def test_an_unknown_disposition_is_spelled_attachment(fake_storage):
    url = s3.presign_get_url(
        OBJECT_KEY,
        filename="pit-loom.jpg",
        mime_type="image/jpeg",
        disposition='inline"; x-injected="1',
    )

    assert _query(url)["response-content-disposition"] == (
        "attachment; filename=\"pit-loom.jpg\"; filename*=UTF-8''pit-loom.jpg"
    )



def test_the_flag_defaults_to_off_in_the_declared_settings():
    field = config.Settings.model_fields["media_presigned_reads"]

    assert field.default is False
    assert field.alias == "MEDIA_PRESIGNED_READS"


def test_the_ttl_defaults_to_fifteen_minutes():
    field = config.Settings.model_fields["media_presigned_read_ttl_seconds"]

    assert field.default == 900
    assert field.alias == "MEDIA_PRESIGNED_READ_TTL_SECONDS"


def test_presigned_read_ttl_survives_an_environment_it_cannot_read(monkeypatch):
    def _explode():
        raise RuntimeError("no environment")

    monkeypatch.setattr(config, "get_settings", _explode)

    assert records.presigned_read_ttl() is None
