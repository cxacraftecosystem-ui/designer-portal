"""What the three upload doors will accept: what ``/media/presign`` and ``/multipart/create`` sign,
how big, and what ``/media/complete`` is allowed to write onto the row.

================================================================================================
THE DEFECT: THE STORED OBJECT'S CONTENT-TYPE WAS CHOSEN BY WHOEVER UPLOADED IT
================================================================================================

``presign_media_upload`` put ``payload.mimeType`` straight into the SIGNED ``Content-Type`` of the
object and into the ``headers`` the client is told to send::

    "uploadUrl": presign_put_url(object_key, payload.mimeType),
    "headers": {"Content-Type": payload.mimeType},

and ``schemas/media.PresignRequest`` bounds that field only by length (``max_length=180``). So any
signed-in account could stage a world-readable ``text/html`` document on the deployment's own
storage host — a phishing page or a credential form living at an ``s3.…amazonaws.com`` / CDN URL the
organisation owns, reachable through ``GET /data/media/{id}/download``'s 307 and through the media
lightbox's "Open" control, which is a top-level navigation. ``components/forms/MediaCaptureField.tsx``
had already written this hole down from the client side, citing the two lines above by number.

``sizeBytes`` was read by nothing on the presign route at all, and on ``/multipart/create`` only to
divide by the part size — a declared 40 GB produced 2,560 part URLs and no refusal.

================================================================================================
WHY THE ALLOW-LIST IS BROAD, AND WHY THAT IS THE SECURITY-RELEVANT CHOICE RATHER THAN A CONCESSION
================================================================================================

Android's ``saveOrQueue`` does not queue a 4xx, so a refused presign LOSES the recording rather than
retrying it. A type this list forgets is therefore a file that never comes back — from a phone in
the field, at the end of a fortnight's sync, with no way to update the build that sent it. An
allow-list narrower than what the fleet actually uploads is not a control; it is an outage with a
security rationale.

So the second section below is the load-bearing one: every MIME type this repository can be SHOWN to
put on this wire, asserted as accepted. Each case names where it comes from. If a future narrowing
breaks one of them, it breaks here rather than in a district office.

================================================================================================
AND A THIRD DOOR, WHICH IS THE ONE THE ROW GOES THROUGH (2026-09-03)
================================================================================================

The two routes above gate the type SIGNED ONTO THE OBJECT. ``MediaCompleteRequest.mimeType`` is a
separate ``str`` that was gated by nothing at all and is stored on the ROW — and the row is what
every read path believes. Section 5 covers it; its header carries the argument.

Nothing here needs a database or a bucket: every gate is a pure function of the declared type, the
declared size and the configured ceiling, and each runs before an object key is minted or a row is
read.
"""

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


# --------------------------------------------------------------------------------------
# 1. The types that made the stored object a document
# --------------------------------------------------------------------------------------


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
    """THE DEFECT. ``text/html`` is the whole of it: the object comes back down labelled as a
    document and the lightbox's "Open" control is a top-level navigation to that URL. The spelling
    variants are here because a deny-list matched case-sensitively, or matched before the
    ``; charset=`` parameter was stripped, would be a deny-list with a door beside it."""
    assert _refusal(mime).status_code == 422


def test_the_deny_list_beats_the_family_it_sits_inside():
    """``text/`` has to be ADMITTED for ``.txt`` and ``.csv`` (both are in ``MediaCaptureField``'s
    ``documentAccept``) and ``text/html`` has to be REFUSED, and one list cannot say both. This is
    the assertion that the order of the two checks is the right way round."""
    assert _refusal("text/html").status_code == 422
    _assert_ok("text/plain")
    _assert_ok("text/csv")


@pytest.mark.parametrize("mime", ["", "   ", "not-a-mime-type", "application/x-shockwave-flash"])
def test_anything_outside_the_families_is_refused(mime):
    """The list is an allow-list, so the default answer is no. An empty or malformed type is
    refused rather than defaulted to something, because the value is about to be SIGNED."""
    assert _refusal(mime).status_code == 422


def test_the_refusal_is_one_terse_sentence():
    """Owner's standing instruction. The argument for the list lives in the banner above it."""
    detail = _refusal("text/html").detail
    assert detail == "text/html cannot be uploaded here."


# --------------------------------------------------------------------------------------
# 2. EVERY TYPE THE FLEET DEMONSTRABLY SENDS. Read from both clients, 2026-09-03.
# --------------------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("mime", "source"),
    [
        # The four MediaType families the product stores.
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
        # The DOCUMENT bucket: MediaCaptureField's documentAccept, resolved to types.
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
    """One case per type, each naming where it comes from, because a narrowing that breaks any of
    them loses a real file from a real device. See this module's header for why that is the
    security-relevant risk here rather than an inconvenience."""
    _assert_ok(mime)


def test_application_octet_stream_is_accepted():
    """THE ONE THE AUDIT ASKED TO BE REFUSED, AND IT IS NOT. **Both** shipped clients emit it as
    their fallback for a file the platform will not type — ``frontend/lib/media.ts``'s ``file.type
    || "application/octet-stream"``, and ``contentResolver.getType(uri) ?:
    "application/octet-stream"`` in Android's ``Offline.kt``, ``WorkshopDraftStore.kt`` and
    ``WorkshopRepository.kt``. ``MediaCaptureField``'s own note records the concrete case: a browser
    with no mapping for ``.glb`` reports an empty type, and ``prototype.modelFile`` is a declared
    registry field. Refusing it would 422 that field from every device.

    It is also the INERT type — a browser downloads it and never renders it — so it is not the type
    the hole this gate closes was ever about."""
    _assert_ok("application/octet-stream")


def test_the_phones_own_apk_is_accepted():
    """``WorkshopRepository.publishAppUpdate`` — the "Push update to all" flow — reads the installed
    APK and presigns it as ``application/vnd.android.package-archive`` through THIS route. Refusing
    it would break over-the-air updates for the whole fleet, from a build already in the field that
    cannot be changed."""
    _assert_ok("application/vnd.android.package-archive")


def test_svg_is_accepted_and_the_belt_stays_on_the_cdn():
    """The one genuinely scriptable type on the list, kept deliberately. The registry asks for it by
    name (``sketch.lineArtFile``: "An SVG or vector export, if one was produced"),
    ``MediaCaptureField``'s ``imageAccept`` lists ``.svg`` explicitly, and that file carries the
    measured argument for why the token stands — the storage host is a DIFFERENT ORIGIN from the
    app, so what executes can read neither this app's storage nor its cookies. The mitigation it
    names is a rule on the bucket/CDN (``Content-Disposition: attachment``, or a
    ``Content-Security-Policy: sandbox`` response header), not a refusal here that would delete a
    declared field's only answer."""
    _assert_ok("image/svg+xml")


def test_a_charset_parameter_does_not_change_the_answer():
    """The same normalisation ``analyze_media_measurement`` already applies to ``file.content_type``,
    so the two gates in this file cannot disagree about whether ``image/jpeg; charset=binary`` is a
    JPEG."""
    _assert_ok("image/jpeg; charset=binary")
    _assert_ok("  IMAGE/JPEG  ")


# --------------------------------------------------------------------------------------
# 3. The declared size
# --------------------------------------------------------------------------------------


def test_a_declared_size_over_the_ceiling_is_413():
    """Read by nothing on this route before. A 413 rather than a 422 because the type was fine and
    the file is simply too big — the client can pick another one."""
    refusal = _refusal("video/mp4", size=2 * GIB)
    assert refusal.status_code == 413
    assert "1024 MB" in refusal.detail


def test_the_largest_object_this_deployment_actually_holds_still_fits():
    """THE NUMBER THE DEFAULT WAS CHOSEN FROM, pinned so a later "tidy" to a rounder 512 MiB has to
    argue with it. docs/SCALABILITY.md §5.1 measures the largest live object at 668.44 MiB, so a
    512 MiB ceiling would refuse a re-upload of a file class the fleet demonstrably produces — and
    Android does not queue a 4xx, so the refusal loses the recording rather than retrying it."""
    _assert_ok("video/mp4", size=int(668.44 * 1024 * 1024))


def test_a_size_exactly_at_the_ceiling_is_accepted():
    """``>`` and not ``>=``: a caller who sends precisely the documented limit must not be refused
    for doing what the error message told them to."""
    _assert_ok("video/mp4", size=GIB, ceiling=GIB)


def test_an_unconfigured_ceiling_disables_the_size_check_only():
    """A deployment that sets ``MEDIA_UPLOAD_MAX_BYTES=0`` has turned off a size ceiling, which is
    an operator's decision to make. It has NOT turned off the type gate, which is the half that
    stops a stored document — so the two must not share a switch."""
    _assert_ok("video/mp4", size=99 * GIB, ceiling=0)
    assert _refusal("text/html", ceiling=0).status_code == 422


def test_no_declared_size_is_not_read_as_zero():
    """A caller that names no size gets the type gate and nothing else, rather than a comparison
    against ``None`` that would raise a 500 out of a validation helper."""
    media._assert_uploadable("image/jpeg", None, _settings())


# --------------------------------------------------------------------------------------
# 4. Both doors, not one
# --------------------------------------------------------------------------------------


def test_both_upload_routes_run_the_same_gate():
    """``/multipart/create`` is the route the fleet uses for anything over the multipart threshold —
    every long video, every large document — and ``create_multipart_upload`` writes the caller's
    ``mimeType`` onto the object exactly as the single-PUT signature does. Gating one and not the
    other would leave the hole open for precisely the large uploads.

    Asserted against the SOURCE because both handlers are ``async`` and reach S3 on the happy path;
    what is worth pinning is that neither can be edited to drop the call without this failing.
    """
    import inspect

    for handler in (media.presign_media_upload, media.create_multipart):
        source = inspect.getsource(handler)
        assert "_assert_uploadable(payload.mimeType, payload.sizeBytes, settings)" in source


# --------------------------------------------------------------------------------------
# 5. THREE DOORS, NOT TWO: the type the ROW is stored with (added 2026-09-03)
#
# The two routes above gate the type that is signed onto the OBJECT.
# ``MediaCompleteRequest.mimeType`` is a SEPARATE, ungated ``str`` that is stored on the ROW, and the
# row is what every read path believes: ``records._sign_media_url`` puts it on a presigned GET as
# ``response-content-type``, and an S3 response override beats the object's own stored header AND any
# CDN ``Content-Disposition`` rule. Presign as ``application/octet-stream``, PUT anything, complete as
# ``text/html``, and with ``MEDIA_PRESIGNED_READS`` on every read of that row served an executable
# page inline from the organisation's own storage host.
#
# The other half of the repair is at the signer; see ``test_media_presigned_reads`` section 2b.
# --------------------------------------------------------------------------------------


def _complete(mime: str, *, user_id: str = "u-the-uploader"):
    """Run ``complete_media_upload`` far enough to reach the type gate. No database is touched.

    THE GATE IS AHEAD OF THE IDEMPOTENCY READ, deliberately — a refusal must leave nothing behind and
    cost no query — which is also what makes this assertable without Postgres. If somebody moves the
    call below ``db.mediafile.find_unique`` this stops raising and starts erroring on a missing
    connection, which is a failure either way.
    """
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
    """THE ATTACK, IN ONE CALL. Presigned as something inert, completed as a document.

    Nothing but a client's good manners ever made the two strings the same value, and it is the
    ``/complete`` one that is stored, read back and signed.
    """
    with pytest.raises(HTTPException) as excinfo:
        _complete("text/html")

    assert excinfo.value.status_code == 422
    assert excinfo.value.detail == "text/html cannot be uploaded here."


@pytest.mark.parametrize("mime", ["application/xhtml+xml", "text/javascript", "TEXT/HTML"])
def test_complete_runs_the_same_deny_list_as_the_presign_door(mime):
    """One predicate, not a second spelling of it — including the case and parameter normalisation.

    A gate that agreed with the presign door on ``text/html`` and disagreed on ``TEXT/HTML`` would be
    a gate with a door beside it, which is the same complaint section 1 makes about the deny-list.
    """
    with pytest.raises(HTTPException) as excinfo:
        _complete(mime)
    assert excinfo.value.status_code == 422


def test_complete_does_not_refuse_what_the_fleet_actually_completes_with():
    """WIRE-COMPAT, WHICH IS THE CONSTRAINT THIS GATE HAD TO BE WRITTEN AROUND.

    Both shipped clients complete with the same type they presigned with — one
    ``contentResolver.getType`` result on the phone, one ``file.type`` on the web — and that value
    has already passed this exact predicate at the presign door. Only a caller that CHANGED the type
    between the two calls is refused.

    Asserted against the PREDICATE rather than by running the handler on: past the gate the route
    reaches the database and object storage, and a test that got that far would either need a
    connection or, worse, find one and write a row. Section 2 above already pins the full list of
    types the fleet sends, and this gate asks that same function.
    """
    for mime in ("image/jpeg", "audio/mp4", "application/pdf", "application/octet-stream"):
        assert media.is_uploadable_mime(mime) is True, f"{mime} would be refused at /media/complete"


def test_the_size_claim_is_not_re_checked_at_complete():
    """``None`` FOR THE SIZE, AND THAT IS THE POINT RATHER THAN AN OMISSION.

    ``sizeBytes`` here is the caller's claim, and this route has already decided not to trust it:
    ``_assert_stored_object_within_ceiling`` asks storage for the real length a few lines below.
    Passing the claim would put a second 413 in front of the only check that is a fact.
    """
    import inspect

    source = inspect.getsource(media.complete_media_upload)
    assert "_assert_uploadable(payload.mimeType, None, settings)" in source
    assert "_assert_stored_object_within_ceiling" in source
