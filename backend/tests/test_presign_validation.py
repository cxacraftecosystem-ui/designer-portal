from types import SimpleNamespace

import pytest
from fastapi import HTTPException

from app.api.routes import media

GIB = 1024 * 1024 * 1024


def _settings(ceiling: int = GIB):
    return SimpleNamespace(media_upload_max_bytes=ceiling)


def _assert_ok(mime: str, size: int = 1024, ceiling: int = GIB) -> None:
    media._assert_uploadable(mime, size, _settings(ceiling))


def _refusal(mime: str, size: int = 1024, ceiling: int = GIB) -> HTTPException:
    with pytest.raises(HTTPException) as excinfo:
        media._assert_uploadable(mime, size, _settings(ceiling))
    return excinfo.value



@pytest.mark.parametrize(
    "mime",
    [
        "text/html",
        "TEXT/HTML",
        "text/html; charset=utf-8",
        "application/xhtml+xml",
        "text/javascript",
        "application/javascript",
        "application/hta",
    ],
)
def test_a_type_a_browser_executes_is_refused(mime):
    assert _refusal(mime).status_code == 422


def test_the_deny_list_beats_the_family_it_sits_inside():
    assert _refusal("text/html").status_code == 422
    _assert_ok("text/plain")
    _assert_ok("text/csv")


@pytest.mark.parametrize("mime", ["", "   ", "not-a-mime-type", "application/x-shockwave-flash"])
def test_anything_outside_the_families_is_refused(mime):
    assert _refusal(mime).status_code == 422


def test_the_refusal_is_one_terse_sentence():
    detail = _refusal("text/html").detail
    assert detail == "text/html cannot be uploaded here."



@pytest.mark.parametrize(
    ("mime", "source"),
    [
        ("image/jpeg", "every camera capture on both clients"),
        ("image/png", "MediaCaptureField imageAccept"),
        ("image/webp", "MediaCaptureField imageAccept"),
        ("image/heic", "iOS photographs; MediaCaptureField imageAccept"),
        ("video/mp4", "MediaCaptureField videoAccept"),
        ("video/quicktime", "MediaCaptureField videoAccept (.mov)"),
        ("video/webm", "MediaCaptureField videoAccept"),
        ("audio/mp4", "Android dictation and interview clips"),
        ("audio/webm", "web MediaRecorder; pickAudioRecorderMimeType"),
        ("audio/mpeg", "MediaCaptureField audioAccept (.mp3)"),
        ("audio/wav", "MediaCaptureField audioAccept"),
        ("audio/amr", "MediaCaptureField audioAccept"),
        ("application/pdf", "documentAccept; both designer-profile CV pickers"),
        ("text/plain", "documentAccept .txt"),
        ("text/csv", "documentAccept .csv"),
        ("application/json", "documentAccept .json"),
        ("application/msword", "documentAccept .doc; DesignerProfileScreen CV picker"),
        (
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "documentAccept .docx; DesignerProfileScreen CV picker",
        ),
        ("application/vnd.oasis.opendocument.text", "DesignerProfileScreen CV picker (.odt)"),
        ("application/vnd.ms-excel", "documentAccept .xls; QuestionnaireInterchangeUi"),
        (
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "documentAccept .xlsx; QuestionnaireInterchangeUi; WorkshopRepository XLSX_MIME",
        ),
        ("model/gltf-binary", "documentAccept .glb, for prototype.modelFile"),
        ("model/gltf+json", "documentAccept .gltf"),
    ],
)
def test_a_type_the_fleet_sends_is_accepted(mime, source):
    _assert_ok(mime)


def test_application_octet_stream_is_accepted():
    _assert_ok("application/octet-stream")


def test_the_phones_own_apk_is_accepted():
    _assert_ok("application/vnd.android.package-archive")


def test_svg_is_accepted_and_the_belt_stays_on_the_cdn():
    _assert_ok("image/svg+xml")


def test_a_charset_parameter_does_not_change_the_answer():
    _assert_ok("image/jpeg; charset=binary")
    _assert_ok("  IMAGE/JPEG  ")



def test_a_declared_size_over_the_ceiling_is_413():
    refusal = _refusal("video/mp4", size=2 * GIB)
    assert refusal.status_code == 413
    assert "1024 MB" in refusal.detail


def test_the_largest_object_this_deployment_actually_holds_still_fits():
    _assert_ok("video/mp4", size=int(668.44 * 1024 * 1024))


def test_a_size_exactly_at_the_ceiling_is_accepted():
    _assert_ok("video/mp4", size=GIB, ceiling=GIB)


def test_an_unconfigured_ceiling_disables_the_size_check_only():
    _assert_ok("video/mp4", size=99 * GIB, ceiling=0)
    assert _refusal("text/html", ceiling=0).status_code == 422


def test_no_declared_size_is_not_read_as_zero():
    media._assert_uploadable("image/jpeg", None, _settings())



def test_both_upload_routes_run_the_same_gate():
    import inspect

    for handler in (media.presign_media_upload, media.create_multipart):
        source = inspect.getsource(handler)
        assert "_assert_uploadable(payload.mimeType, payload.sizeBytes, settings)" in source



def _complete(mime: str, *, user_id: str = "u-the-uploader"):
    import asyncio

    from app.schemas.media import MediaCompleteRequest

    payload = MediaCompleteRequest(
        originalFilename="pit-loom.jpg",
        mediaType="IMAGE",
        mimeType=mime,
        sizeBytes=1024,
        objectKey=f"media/{user_id}/pit-loom-030920261412.jpg",
    )
    return asyncio.run(
        media.complete_media_upload(payload, current_user=SimpleNamespace(id=user_id))
    )


def test_complete_refuses_a_type_that_was_swapped_after_the_presign():
    with pytest.raises(HTTPException) as excinfo:
        _complete("text/html")

    assert excinfo.value.status_code == 422
    assert excinfo.value.detail == "text/html cannot be uploaded here."


@pytest.mark.parametrize("mime", ["application/xhtml+xml", "text/javascript", "TEXT/HTML"])
def test_complete_runs_the_same_deny_list_as_the_presign_door(mime):
    with pytest.raises(HTTPException) as excinfo:
        _complete(mime)
    assert excinfo.value.status_code == 422


def test_complete_does_not_refuse_what_the_fleet_actually_completes_with():
    for mime in ("image/jpeg", "audio/mp4", "application/pdf", "application/octet-stream"):
        assert media.is_uploadable_mime(mime) is True, f"{mime} would be refused at /media/complete"


def test_the_size_claim_is_not_re_checked_at_complete():
    import inspect

    source = inspect.getsource(media.complete_media_upload)
    assert "_assert_uploadable(payload.mimeType, None, settings)" in source
    assert "_assert_stored_object_within_ceiling" in source
