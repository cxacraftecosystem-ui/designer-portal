"""``MEDIA_PRESIGNED_READS``: the flag, the encoder swap, and the two things that must not move.

================================================================================================
WHAT THIS IS ABOUT
================================================================================================

``MediaFile.url`` is ``s3.public_url_for_key(objectKey)`` — the CDN host plus the key. No signature,
no expiry, no authentication, and a bucket policy (``PublicReadMedia``) that makes the object
world-readable to anyone holding the string. ``records._redact_sensitive`` decides WHO is handed it;
nothing decides how long it stays good, so a URL that crossed once has crossed for ever: past the
revocation of the grant that produced it, past the suspension of the account that held it, past the
deletion of the row.

The repair is two moves and they cannot be made together. The server stops emitting the permanent
URL and emits a 15-minute signed one (``MEDIA_PRESIGNED_READS``, this file); then a human removes
``PublicReadMedia`` from the bucket policy in the console, at which point every leaked URL 403s. The
flag exists because Android 0.0.7 is in the field caching ``url`` in its offline store and rendering
photographs from the cached string with no network — expiring URLs handed to THAT build make a
designer's workshop silently lose its images on a phone that may not see a network for a week.

So this suite is about SEQUENCING as much as about signing, and the first section is the important
one: with the flag off, nothing changed. That is not a formality. It is the claim that lets this code
ship to production today, weeks before the flag is turned on, with the fleet still on 0.0.7.

================================================================================================
FOUR THINGS ARE PINNED, AND EACH ONE IS A DIFFERENT WAY TO GET THIS WRONG
================================================================================================

1. **FLAG OFF IS BYTE-IDENTICAL.** Not "equivalent", not "the same fields" — the same dict, and the
   signer NEVER CALLED. A change that signed and then threw the result away would pass an equality
   check on the URL while adding a boto3 client build to every media node of every list in the API.
2. **FLAG ON SIGNS, AND ONLY WHERE THE ENTITLEMENT LAYER SAID YES.** The withholding arm and the
   signing arm are exclusive by construction (``if`` / ``elif`` in ``_redact_sensitive``), and the
   test that would go red if somebody flattened them into two independent conditions is
   ``test_a_withheld_node_is_never_signed``: a refused node has no ``objectKey`` left to sign from,
   so a flattened version would not merely leak — it would leak a URL minted from a key it had just
   decided the caller may not have.
3. **THE STORED COLUMN DOES NOT MOVE.** The flip-back path is "set the flag false", with no data
   migration; ``/data/media/{id}/download``'s 307 and the research-zip manifest's fast path both
   read the column. An encoder that rewrote the row would make this decision one-way on the day it
   was turned on.
4. **THE SIGNATURE IS REALLY A SIGNATURE, AND IT IS INLINE FOR WHAT A PAGE RENDERS.** Section 4 runs
   the real botocore signer (no network, no credentials — presigning is local HMAC) and reads the
   query string back. ``response-content-disposition=inline`` is the one that had to be argued for:
   browsers ignore ``Content-Disposition`` on ``<img>``/``<video>``/``<audio>`` subresources, so
   ``attachment`` would have looked right on three of the four rendering paths while turning every
   inline PDF preview on both clients (``DocumentPreview.tsx``'s ``<object data>``,
   ``DwDocumentPreview.kt``) into a download prompt.
5. **WHAT IS SIGNED IS NOT THE ROW'S OWN ``mimeType`` — added 2026-09-03, section 2b.** That column
   holds whatever a caller sent to ``/media/complete``, and an S3 ``response-content-type`` override
   beats the stored object header AND any CDN ``Content-Disposition`` rule — so this signature was
   the last word on how a browser received an uploaded file, and it said "render it, as whatever
   they called it". A row typed ``text/html`` served an executable page inline from the
   organisation's own storage host on every read. Type and disposition now come from
   ``api.routes.media.signed_read_headers``: the upload allow-list decides whether the caller's type
   survives at all, and a narrower inline set decides whether a browser may render it.

NOTHING HERE NEEDS A DATABASE OR A BUCKET. The encoder is a pure walk over an already-encoded dict,
the flag is a pure read of settings, and ``generate_presigned_url`` is arithmetic. That is the honest
level for all four claims, and it is why this module carries no ``needs_db``.
"""

import json
from types import SimpleNamespace

import pytest

from app.core import config
from app.services import records, s3

# --------------------------------------------------------------------------------------
# Fixtures: one media node, one viewer, one fake storage configuration
# --------------------------------------------------------------------------------------

UPLOADER = "user-the-researcher-who-recorded-it"
VIEWER = "user-the-researcher-reading-it"
STRANGER = "user-nobody-has-granted-anything-to"
OBJECT_KEY = "media/user-the-researcher-who-recorded-it/pit-loom-photo-1-030920261412.jpg"
STORED_URL = f"https://cdn.example.test/{OBJECT_KEY}"


def _encoded_media(**extra) -> dict:
    """One MediaFile as ``jsonable_encoder`` hands it to the walk.

    Hand-built for the reason ``test_media_entitlement`` builds its own: what is under test is a
    predicate over an ALREADY-ENCODED payload. The walk never sees a row — only a dict carrying the
    ``objectKey`` marker, its own ``uploadedById``, and the keys that hand over bytes.
    """
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
    """A caller ``public_encode`` can read an id and a rank off. Not a database row."""
    return SimpleNamespace(id=user_id, role=role, canDownloadDataset=False)


class _Recorder:
    """A stand-in for ``s3.presign_get_url`` that answers, and remembers being asked.

    Both halves matter. ``calls`` is how section 1 proves the signer was not merely ineffective but
    NOT REACHED — a flag that signs and discards would satisfy an assertion about the URL and put a
    boto3 client build on every media node in the API.
    """

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
    """The recorder, installed where ``_sign_media_url``'s deferred import will find it.

    ``records._sign_media_url`` does ``from app.services.s3 import presign_get_url`` INSIDE the
    function, so the name is resolved off the module object at call time — patching the attribute on
    ``app.services.s3`` is what reaches it. Patching ``records.presign_get_url`` would patch nothing,
    because there is no such module global, and the test would pass by never signing at all.
    """
    recorder = _Recorder()
    monkeypatch.setattr(s3, "presign_get_url", recorder)
    return recorder


@pytest.fixture
def flag(monkeypatch):
    """Turn ``MEDIA_PRESIGNED_READS`` on or off through the SETTINGS, not through a patched helper.

    ``records.presigned_read_ttl`` does its own deferred ``from app.core.config import get_settings``,
    so this drives the real decision function over a fake environment. Patching
    ``records.presigned_read_ttl`` itself would have tested the wiring and skipped the flag — and the
    flag is the part an operator sets.
    """

    def _set(enabled: bool, ttl: int = 900) -> None:
        monkeypatch.setattr(
            config,
            "get_settings",
            lambda: SimpleNamespace(
                media_presigned_reads=enabled, media_presigned_read_ttl_seconds=ttl
            ),
        )

    return _set


# --------------------------------------------------------------------------------------
# 1. FLAG OFF: nothing changed, and the signer was never reached
# --------------------------------------------------------------------------------------


def test_with_the_flag_off_the_payload_is_what_it_was(flag, signer):
    """THE CLAIM THAT LETS THIS SHIP BEFORE THE FLEET MOVES.

    Compared as serialised JSON rather than as dicts, and with ``sort_keys=False``, so KEY ORDER is
    pinned too: a client that reads a payload positionally does not exist here, but a diff that
    reordered keys would be a diff, and "byte-identical" is the promise this deployment is being
    asked to trust while every fielded 0.0.7 handset keeps caching what it is handed.
    """
    flag(False)
    node = _encoded_media()
    before = json.dumps(node)

    out = records.public_encode(node, _viewer(user_id=UPLOADER))

    assert json.dumps(out) == before
    assert out["url"] == STORED_URL
    assert signer.calls == [], "the signer must not be reached at all while the flag is off"


def test_with_the_flag_off_a_professor_sees_exactly_what_they_saw(flag, signer):
    """The ``ALL_MEDIA_URLS`` path, which skips the withholding arm entirely.

    Worth its own case because the signing arm hangs off the SAME ``if``: a professor's node never
    enters the withholding branch, so if the two arms were ever written as independent statements the
    professor path is where an unguarded signing call would show up first.
    """
    flag(False)
    out = records.public_encode(_encoded_media(), _viewer(user_id=VIEWER, role="PROFESSOR"))

    assert out["url"] == STORED_URL
    assert signer.calls == []


def test_a_zero_ttl_is_read_as_off(flag, signer):
    """``MEDIA_PRESIGNED_READ_TTL_SECONDS=0`` is somebody disabling the feature by the wrong lever.

    A zero-second signature is a URL that is expired before it is sent, so the less astonishing
    reading of that intent is "serve the stored one" — and it must not be the loud reading, because
    the surface it would break is every photograph in the repository at once.
    """
    flag(True, ttl=0)
    out = records.public_encode(_encoded_media(), _viewer(user_id=UPLOADER))

    assert out["url"] == STORED_URL
    assert signer.calls == []


# --------------------------------------------------------------------------------------
# 2. FLAG ON: the entitled node is signed, the refused node is not
# --------------------------------------------------------------------------------------


def test_with_the_flag_on_an_entitled_url_is_signed(flag, signer):
    """The swap itself, and the four arguments that make it the right signature.

    The TTL comes from settings rather than from a constant in the encoder, the object key comes off
    the node rather than from the stored URL (they are the same string today and the row is the
    source of truth), the filename is the ARCHIVE name a researcher matches against their own copy,
    and the disposition is inline. Each is asserted because each has a plausible wrong answer.
    """
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
    """RBAC IS UNCHANGED, AND THE SIGNING ARM CANNOT REACH PAST IT.

    A stranger's file, read by an account with no grant: the takeable keys go, as they always did.
    The extra claim is ``signer.calls == []`` — the arm is an ``elif`` on the withholding arm, so a
    refused node is not merely un-signed, it is never OFFERED to the signer. Flattening those two
    into independent conditions would mint a URL from an ``objectKey`` the walk had just decided this
    caller may not have, which is a worse leak than the one this whole feature is closing.
    """
    flag(True)
    out = records.public_encode(_encoded_media(uploadedById=STRANGER), _viewer(user_id=VIEWER))

    for key in records._MEDIA_TAKEABLE_KEYS:
        assert key not in out, f"{key} must not survive for an unentitled caller"
    assert signer.calls == []


def test_the_design_workshop_arm_still_admits_and_now_signs(flag, signer):
    """A co-designer's workshop photograph: admitted by the tag arm, and signed like any other.

    The two arms of the entitlement test (uploader set, workshop tag) must both lead to the same
    signing behaviour. If only the uploader arm signed, a co-designer would keep receiving permanent
    URLs after the flag went on — and would be the last surface still working after the bucket flip,
    which is exactly how a forgotten path stays forgotten.
    """
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
    """The walk reaches media wherever it is, which is the whole reason the swap lives in the walk.

    A record with an embedded gallery is the ordinary shape of every list this API serves — products,
    tools, processes, artisans, workshops. A per-route swap would have had to find each of them.
    """
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
    """``url: null`` IS A STATE, NOT A HOLE.

    An upload that never completed, or a deployment with no ``AWS_S3_PUBLIC_BASE_URL``, stores NULL —
    and both clients read that as "there is nothing to play here" (``DocumentPreview.tsx`` tests
    ``!file.url`` above everything else). Minting a URL into that hole would be a behaviour change
    wearing a security change's clothes, and it would put a play button on a file that may not exist.
    """
    flag(True)
    out = records.public_encode(_encoded_media(url=None), _viewer(user_id=UPLOADER))

    assert out["url"] is None
    assert signer.calls == []


def test_a_signing_failure_falls_back_to_the_stored_url(flag, monkeypatch):
    """A misconfigured object store must not turn a list of four hundred rows into an error page.

    The same trade ``datasets._presign_media_row`` and ``export.dataset_manifest`` already make, and
    for the same reason. Note what it means AFTER the bucket flip: the caller gets a URL that 403s,
    the client tries to refresh it, and the refresh fails the same way — so a deployment that cannot
    sign after the flip is broken loudly, at the client, on every image. That is the right place for
    it to be loud, and it is a great deal better than a 500 on every read route in the API.
    """
    flag(True)

    def _explode(*args, **kwargs):
        raise RuntimeError("no credentials configured")

    monkeypatch.setattr(s3, "presign_get_url", _explode)
    out = records.public_encode(_encoded_media(), _viewer(user_id=UPLOADER))

    assert out["url"] == STORED_URL


# --------------------------------------------------------------------------------------
# 2b. WHAT IS SIGNED IS NOT THE ROW'S OWN STRING (added 2026-09-03)
#
# ``MediaFile.mimeType`` is the CALLER'S string: ``/media/complete`` accepted any ``str`` and wrote
# it down, ungated, while ``_assert_uploadable`` guarded only the presign door. And an S3
# ``response-content-type`` override beats both the object's stored header and any CDN
# ``Content-Disposition`` rule — so the signature below was the LAST word on how a browser received a
# file somebody uploaded, and it used to say "render it, as whatever they called it". Presign as
# ``application/octet-stream``, complete as ``text/html``, and with this flag on every read of that
# row served an executable page inline from the organisation's own storage host — reachable from the
# lightbox's "Open" control and from ``/data/media/{id}/download``'s 307.
#
# The other chokepoint is the create itself; see ``test_presign_validation``.
# --------------------------------------------------------------------------------------


def test_an_html_typed_row_is_signed_as_an_inert_attachment(flag, signer):
    """THE ATTACK, AT THE SIGNER.

    Both halves are asserted because either alone leaves it open: ``attachment`` with a
    ``text/html`` content type still lets a browser sniff and render in some configurations, and
    ``text/html`` inline is the hole itself. The type signed is ``application/octet-stream`` — the
    inert fallback both clients already send for a file the platform will not name, which a browser
    downloads and never executes.
    """
    flag(True)
    records.public_encode(_encoded_media(mimeType="text/html"), _viewer(user_id=UPLOADER))

    assert len(signer.calls) == 1
    assert signer.calls[0]["mime_type"] == "application/octet-stream"
    assert signer.calls[0]["disposition"] == "attachment"


def test_an_svg_keeps_its_type_and_is_never_inline(flag, signer):
    """THE ONE SCRIPTABLE TYPE THIS DEPLOYMENT DELIBERATELY ACCEPTS.

    ``sketch.lineArtFile`` asks for SVG by name and ``MediaCaptureField``'s ``imageAccept`` offers
    it, so refusing the upload would delete a declared field's only answer — the upload allow-list's
    own banner says the belt belongs on the DISTRIBUTION. This is that belt, and it is in the
    signature rather than in a CDN rule because an S3 response override beats the CDN.

    The type is NOT rewritten: an SVG is a legitimate upload here, and a gallery still renders it,
    because browsers ignore ``Content-Disposition`` on an ``<img>`` subresource. What changes is
    navigating to the URL, which is the case where the ``<script>`` inside would have run.
    """
    flag(True)
    records.public_encode(_encoded_media(mimeType="image/svg+xml"), _viewer(user_id=UPLOADER))

    assert signer.calls[0]["mime_type"] == "image/svg+xml"
    assert signer.calls[0]["disposition"] == "attachment", (
        "an SVG navigated to is an HTML document with script in it, whatever the picker calls it"
    )


def test_a_jpeg_is_still_inline_and_still_its_own_type(flag, signer):
    """THE REGRESSION GUARD ON THE OTHER SIDE, and the reason this is a table rather than a ban.

    Every photograph, clip and recording in the product goes through this line. A defensive rewrite
    that signed everything as an attachment would have looked correct — browsers ignore the header
    on image/video/audio subresources — while turning every inline PDF preview on both clients into
    a download prompt, which is the failure ``presign_get_url`` grew its parameter to avoid.
    """
    flag(True)
    records.public_encode(_encoded_media(), _viewer(user_id=UPLOADER))

    assert signer.calls[0]["mime_type"] == "image/jpeg"
    assert signer.calls[0]["disposition"] == "inline"


def test_a_pdf_is_inline_and_a_spreadsheet_is_not(flag, signer):
    """The named exception and the ordinary document, asserted as a pair.

    PDF is the caller that forced the ``inline`` parameter into existence (``DocumentPreview.tsx``'s
    ``<object data>``, ``DwDocumentPreview.kt``), and it is a sandboxed renderer rather than a
    document context. An ``.xlsx`` is downloaded by every client that touches it, so inline buys
    nothing and the narrower answer is free.
    """
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
    """``mimeType`` NULL is not a licence to guess.

    The old line defaulted to ``application/octet-stream`` and signed it INLINE, which no browser
    renders anyway — so the answer is unchanged in effect and now says what it means.
    """
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
    """ONE PREDICATE, NOT TWO SPELLINGS OF ONE.

    The read side asks ``is_uploadable_mime`` — the same function ``_assert_uploadable`` asks — so a
    type added to or removed from the upload allow-list cannot leave the signer answering last
    year's question. A second copy would be one edit away from disagreeing, and a security gate that
    disagrees with itself is the shape of the hole.
    """
    from app.api.routes import media as media_routes

    assert media_routes.is_uploadable_mime("image/jpeg") is True
    assert media_routes.is_uploadable_mime("text/html") is False
    for denied in media_routes._UPLOAD_MIME_DENIED:
        assert media_routes.signed_read_headers(denied) == (
            "application/octet-stream",
            "attachment",
        ), f"{denied} is refused at the door and must be inert on the way out too"


# --------------------------------------------------------------------------------------
# 3. THE STORED COLUMN DOES NOT MOVE
# --------------------------------------------------------------------------------------


def test_the_source_row_is_untouched_by_signing(flag, signer):
    """The flip-back path, pinned.

    ``public_encode`` runs ``jsonable_encoder`` first, which copies — so the object handed in keeps
    its permanent URL and the database is never asked to change. "Set the flag false" is then the
    entire rollback: no migration, no backfill, no window in which some rows are signed and others
    are not. A ``SimpleNamespace`` stands in for the Prisma row because what is being asserted is
    that the ENCODER does not write back, which no database can make more true.
    """
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


# --------------------------------------------------------------------------------------
# 4. THE SIGNATURE IS REALLY A SIGNATURE (real botocore, no network, no credentials)
# --------------------------------------------------------------------------------------


@pytest.fixture
def fake_storage(monkeypatch):
    """Point ``s3`` at a plausible AWS configuration that exists only in this process.

    Presigning is local HMAC over the request the client would have sent, so this needs no
    credentials that work, no bucket that exists and no network. It is the only way to assert the
    SHAPE of what a browser actually receives rather than the shape of a stub.
    """
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
    """The query-param shape a client and a reviewer can both check.

    ``X-Amz-Expires`` is the one that makes the whole feature true: it is the difference between a
    string that stops working and a string that does not. It is asserted as the TTL that was asked
    for, not merely as present, because a signer that silently clamped it would be a leak with a
    passing test.
    """
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
    """EVERY CALLER THAT EXISTED BEFORE 2026-09-03 ASKED FOR THIS BY NOT ASKING.

    The app-release download, the dataset media index and the research-zip manifest all hand the URL
    to something that SAVES a file, and ``attachment`` is what puts the chosen filename on it. A
    parameter added for the inline case that changed the default would have changed all three
    silently.
    """
    url = s3.presign_get_url(
        OBJECT_KEY, filename="pit-loom.jpg", mime_type="image/jpeg", expires_in=900
    )

    assert _query(url)["response-content-disposition"] == 'attachment; filename="pit-loom.jpg"'


def test_an_inline_disposition_is_what_the_encoder_asks_for(fake_storage):
    """The parameter the PDF preview forced.

    Browsers ignore ``Content-Disposition`` on ``<img>``/``<video>``/``<audio>`` subresources, so
    ``attachment`` would have rendered correctly on three of the four paths this URL is used on and
    turned every inline PDF preview on both clients into a download prompt. That is the kind of
    regression that reads as "the PDF viewer broke" three weeks after the flag went on.
    """
    url = s3.presign_get_url(
        OBJECT_KEY,
        filename="pit-loom.jpg",
        mime_type="image/jpeg",
        expires_in=900,
        disposition="inline",
    )
    params = _query(url)

    assert params["response-content-disposition"] == 'inline; filename="pit-loom.jpg"'
    assert params["response-content-type"] == "image/jpeg"


def test_an_unknown_disposition_is_spelled_attachment(fake_storage):
    """Anything that is not "inline" is spelled, not interpolated.

    The parameter is a header directive. Building it by interpolation would let a caller smuggle a
    second directive — or a header — through a string that looks like a mode. Two values, one of them
    the default, and everything else collapses to the safe one.
    """
    url = s3.presign_get_url(
        OBJECT_KEY,
        filename="pit-loom.jpg",
        mime_type="image/jpeg",
        disposition='inline"; x-injected="1',
    )

    assert _query(url)["response-content-disposition"] == 'attachment; filename="pit-loom.jpg"'


# --------------------------------------------------------------------------------------
# 5. THE FLAG ITSELF
# --------------------------------------------------------------------------------------


def test_the_flag_defaults_to_off_in_the_declared_settings():
    """READ OFF THE FIELD DECLARATION, not off a constructed ``Settings``.

    ``Settings`` has required fields (a database URL, AWS credentials, a master admin email) and CI
    exports none of them, so constructing one here would skip on some machines and pass on others —
    which is the shape of a default nobody is actually checking. The model field carries the default,
    and that is what an operator who sets nothing gets.
    """
    field = config.Settings.model_fields["media_presigned_reads"]

    assert field.default is False
    assert field.alias == "MEDIA_PRESIGNED_READS"


def test_the_ttl_defaults_to_fifteen_minutes():
    """Sized for "a page renders, a person looks at it, a person clicks download".

    The surfaces that outlive a look — the dataset manifest, the research zip — sign their own at six
    hours and are named in ``records._sign_media_url``'s decision table. Raising this one is how that
    distinction quietly stops being a distinction.
    """
    field = config.Settings.model_fields["media_presigned_read_ttl_seconds"]

    assert field.default == 900
    assert field.alias == "MEDIA_PRESIGNED_READ_TTL_SECONDS"


def test_presigned_read_ttl_survives_an_environment_it_cannot_read(monkeypatch):
    """No settings, no change in behaviour — never an exception.

    ``presigned_read_ttl`` runs inside ``public_encode``, which is on every read route in the API. An
    encoder that raised because an environment variable was missing would take the whole read surface
    down to turn on a feature that is off by default.
    """

    def _explode():
        raise RuntimeError("no environment")

    monkeypatch.setattr(config, "get_settings", _explode)

    assert records.presigned_read_ttl() is None
