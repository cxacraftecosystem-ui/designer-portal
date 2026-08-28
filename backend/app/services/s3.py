"""S3 object storage: presigned uploads, multipart uploads and object access.

Security shape of this module (the full picture, including the console-side pieces, is in
docs/SECURITY.md):

* **In transit** — every AWS endpoint built here is ``https://``, so bytes are TLS-protected on the
  browser/phone -> S3 hop as well as on the API -> S3 hop. A custom ``AWS_S3_ENDPOINT`` (MinIO) is
  honoured verbatim; a plaintext one is logged as a warning unless it is a local dev host.
* **At rest** — the multipart path requests SSE-S3 explicitly. The single-PUT presign path *cannot*
  (see ``presign_put_url``), so encryption for those objects comes from the bucket's **default
  encryption** setting, which S3 applies server-side to every object regardless of what the client
  sends. That bucket setting is the load-bearing control; the header here is belt-and-braces.
"""

import logging
import os
import tempfile
import threading
from dataclasses import dataclass
from functools import lru_cache
from pathlib import PurePath
from typing import Any
from urllib.parse import urlsplit
from uuid import uuid4

import boto3
from botocore.client import Config

from app.core.config import get_settings

logger = logging.getLogger(__name__)

# Hosts where a plaintext object-storage endpoint is expected and harmless (local MinIO).
_LOCAL_ENDPOINT_HOSTS = frozenset({"localhost", "127.0.0.1", "::1", "minio", "host.docker.internal"})
_insecure_endpoint_warned = False


def _warn_if_insecure_endpoint(endpoint: str | None) -> None:
    """Log once if media bytes would travel to storage over plaintext HTTP.

    Uploads go CLIENT -> storage directly (presigned URL), so a plaintext endpoint means every
    photo, recording and transcript crosses the network unencrypted, and the presigned URL itself —
    a bearer credential for writing that object — is exposed to anyone on the path. Local MinIO on
    a loopback host is exempt.
    """
    global _insecure_endpoint_warned
    if not endpoint or _insecure_endpoint_warned:
        return
    parts = urlsplit(endpoint)
    if parts.scheme != "http":
        return
    if (parts.hostname or "").lower() in _LOCAL_ENDPOINT_HOSTS:
        return
    _insecure_endpoint_warned = True
    logger.error(
        "AWS_S3_ENDPOINT is plaintext HTTP (%s): media and presigned upload URLs are exposed in "
        "transit. Point it at an https:// endpoint.",
        endpoint,
    )


def _sse_params() -> dict[str, str]:
    """Server-side-encryption parameters for uploads the API itself starts.

    Empty when ``AWS_S3_SSE_ALGORITHM`` is blank — local MinIO without a KMS backend rejects the
    header outright, and failing an upload is worse than falling back to the storage layer's own
    at-rest behaviour in development.
    """
    algorithm = (get_settings().aws_s3_sse_algorithm or "").strip()
    return {"ServerSideEncryption": algorithm} if algorithm else {}


@lru_cache(maxsize=4)
def _build_client(
    region: str | None,
    endpoint: str | None,
    access_key: str | None,
    secret_key: str | None,
    addressing_style: str | None,
):
    """The boto3 client itself, memoised on everything that shapes it.

    ONE CLIENT, NOT ONE PER OBJECT. Each ``boto3.client`` builds its own ``URLLib3Session``
    connection pool, so constructing one per call meant no S3 connection was ever reused: every
    photograph embedded in a report paid a fresh TCP+TLS handshake instead of riding a keep-alive
    socket, and ``GET /api/datasets/media.ndjson?presign=true`` built one client per row of a table
    that legitimately runs into five figures. Botocore clients are safe to share across threads for
    the calls this module makes (``get_object``, ``generate_presigned_url``, the multipart trio) —
    what is not thread-safe is a boto3 *resource*, which this module never creates.

    Keyed on the settings rather than cached bare, so a process that reconfigures storage — a test
    pointing at a different endpoint, a rotated credential — gets a client that matches. ``maxsize``
    is small on purpose: a handful of distinct configurations is a real deployment, a hundred is a
    leak.
    """
    return boto3.client(
        "s3",
        region_name=region,
        endpoint_url=endpoint,
        aws_access_key_id=access_key,
        aws_secret_access_key=secret_key,
        # SigV4 so presigned PUTs validate in every region.
        config=Config(
            signature_version="s3v4",
            s3={} if addressing_style is None else {"addressing_style": addressing_style},
        ),
    )


def _client():
    settings = get_settings()
    # For a real AWS bucket outside us-east-1, presign against the *regional* endpoint. The global
    # endpoint (bucket.s3.amazonaws.com) 307-redirects to the regional host, which changes the Host
    # header the client sends and breaks the SigV4 signature -> 403 SignatureDoesNotMatch. Pinning
    # the regional endpoint (bucket.s3.<region>.amazonaws.com) keeps the signed host and the request
    # host identical. A custom endpoint (MinIO) is honoured as-is.
    endpoint = settings.aws_s3_endpoint
    # Virtual-hosted addressing only for real AWS (regional endpoint). A custom endpoint such as
    # MinIO needs path-style, so leave its addressing on boto3's default ("auto").
    addressing_style: str | None = None
    if not endpoint and settings.aws_region:
        # Dual-stack regional endpoint (s3.dualstack.<region>) so presigned PUT URLs resolve a
        # native IPv6 (AAAA) address. IPv4-only mobile data is increasingly IPv6-only (Jio/Airtel);
        # the plain s3.<region> host has no AAAA, so uploads from such phones fail to connect.
        # Dual-stack serves IPv4 too, so Wi-Fi is unaffected, and SigV4 signs the dual-stack host.
        endpoint = f"https://s3.dualstack.{settings.aws_region}.amazonaws.com"
        addressing_style = "virtual"
    # Outside the memoised builder deliberately: the warning is once-per-process either way (see
    # `_insecure_endpoint_warned`), and a plaintext endpoint should still be evaluated on every
    # call rather than only on the one that happened to miss the cache.
    _warn_if_insecure_endpoint(endpoint)
    return _build_client(
        settings.aws_region,
        endpoint,
        settings.aws_access_key_id,
        settings.aws_secret_access_key,
        addressing_style,
    )


def safe_filename(filename: str) -> str:
    basename = PurePath(filename).name.strip().replace("\\", "-").replace("/", "-")
    cleaned = "".join(ch if ch.isalnum() or ch in {".", "-", "_"} else "-" for ch in basename)
    return cleaned or "upload.bin"


def make_object_key(user_id: str, filename: str) -> str:
    return f"media/{user_id}/{uuid4().hex}/{safe_filename(filename)}"


def _promote_dualstack(url: str, region: str | None) -> str:
    """Rewrite a regional AWS S3 host to its dual-stack form so stored media URLs resolve a native
    IPv6 address on IPv6-only mobile networks. Idempotent, and a no-op when the host isn't the
    plain regional S3 endpoint (e.g. a custom CDN/MinIO base)."""
    if not region:
        return url
    plain = f".s3.{region}.amazonaws.com"
    dual = f".s3.dualstack.{region}.amazonaws.com"
    if dual in url or plain not in url:
        return url
    return url.replace(plain, dual)


def public_url_for_key(object_key: str) -> str | None:
    settings = get_settings()
    if settings.aws_s3_public_base_url:
        base = f"{settings.aws_s3_public_base_url.rstrip('/')}/{object_key}"
    elif settings.aws_s3_endpoint:
        # Custom endpoint (MinIO/CDN) is served verbatim — no dual-stack promotion.
        return f"{settings.aws_s3_endpoint.rstrip('/')}/{settings.aws_s3_bucket}/{object_key}"
    else:
        return None
    # Promote the public base only when it points at a real AWS regional host (not a custom endpoint).
    if not settings.aws_s3_endpoint:
        base = _promote_dualstack(base, settings.aws_region)
    return base


def presign_put_url(object_key: str, mime_type: str) -> str:
    """Presigned PUT URL for a whole (small) file, valid for 15 minutes.

    **Why there is no ``ServerSideEncryption`` here, deliberately.** Adding it would put
    ``x-amz-server-side-encryption: AES256`` into the SigV4 *signed headers*, which makes the header
    mandatory for the client: every PUT that omits it fails with ``SignatureDoesNotMatch``. The web
    client and the Android client both send only the headers ``/media/presign`` hands them
    (currently ``Content-Type``), and Android builds already installed in the field can never be
    updated retroactively — so signing the header here would break all existing uploads.

    Objects uploaded this way are still encrypted at rest, by the bucket's **default encryption**
    setting: S3 applies it server-side to every object, whatever the client sends, and it needs no
    cooperation from the signature. docs/SECURITY.md has the exact bucket configuration (default
    SSE-S3 + a policy denying non-TLS requests) that a human must apply in the console.
    """
    settings = get_settings()
    return _client().generate_presigned_url(
        ClientMethod="put_object",
        Params={
            "Bucket": settings.aws_s3_bucket,
            "Key": object_key,
            "ContentType": mime_type,
        },
        ExpiresIn=900,
        HttpMethod="PUT",
    )


def presign_get_url(object_key: str, *, filename: str, mime_type: str, expires_in: int = 900) -> str:
    """Presigned GET URL that also dictates how the browser receives the object.

    ``public_url_for_key`` cannot do this job. S3 honours the ``response-content-*`` overrides only
    on a **signed** request — an anonymous GET carrying them is rejected outright — so a download
    that has to arrive as an attachment, under a chosen filename and content type, must be signed
    even when the object itself is world-readable. That matters when the stored object's own
    metadata is wrong or its key spells a name nobody should see: the overrides win, whatever the
    object says.

    The signature also expires, which is the point for anything a page hands out as a link: a URL
    that stops working cannot be bookmarked, mailed on, or cached by an intermediary and quietly
    replayed months later.
    """
    settings = get_settings()
    return _client().generate_presigned_url(
        ClientMethod="get_object",
        Params={
            "Bucket": settings.aws_s3_bucket,
            "Key": object_key,
            "ResponseContentType": mime_type,
            "ResponseContentDisposition": f'attachment; filename="{safe_filename(filename)}"',
        },
        ExpiresIn=expires_in,
        HttpMethod="GET",
    )


def create_multipart_upload(object_key: str, mime_type: str) -> str:
    """Begin an S3 multipart upload (for large files). Returns the UploadId the client uploads parts
    against; S3 stitches the parts into one object on complete, so the stored file stays whole.

    Requests server-side encryption at rest explicitly (SSE-S3 / AES-256 by default). This call is
    made by the API with its own credentials, not presigned, so the encryption header costs the
    client nothing — unlike the single-PUT path above. The parts themselves inherit the setting, so
    ``presign_upload_part`` needs no encryption header either."""
    response = _client().create_multipart_upload(
        Bucket=get_settings().aws_s3_bucket,
        Key=object_key,
        ContentType=mime_type,
        **_sse_params(),
    )
    return str(response["UploadId"])


def presign_upload_part(object_key: str, upload_id: str, part_number: int) -> str:
    """Presigned PUT URL for one part, so the (large) bytes go straight to S3, never via the API."""
    return _client().generate_presigned_url(
        ClientMethod="upload_part",
        Params={
            "Bucket": get_settings().aws_s3_bucket,
            "Key": object_key,
            "UploadId": upload_id,
            "PartNumber": part_number,
        },
        ExpiresIn=3600,
        HttpMethod="PUT",
    )


def complete_multipart_upload(object_key: str, upload_id: str, parts: list[dict[str, Any]]) -> None:
    """Finalise the multipart upload — S3 assembles the parts into a single object."""
    _client().complete_multipart_upload(
        Bucket=get_settings().aws_s3_bucket,
        Key=object_key,
        UploadId=upload_id,
        MultipartUpload={"Parts": parts},
    )


def abort_multipart_upload(object_key: str, upload_id: str) -> None:
    """Discard an interrupted multipart upload so its uploaded parts don't linger and incur storage."""
    _client().abort_multipart_upload(
        Bucket=get_settings().aws_s3_bucket,
        Key=object_key,
        UploadId=upload_id,
    )


def get_object_bytes(object_key: str) -> bytes:
    """The whole object, in the heap, in one contiguous ``bytes``.

    **THE RIGHT CALL ONLY WHEN THE CALLER GENUINELY NEEDS EVERY BYTE AT ONCE AND THE OBJECT IS
    KNOWN TO BE SMALL.** That is a narrower set of callers than it looks: a vision model wants
    base64 of the whole image, and there is no way to base64 half a file. Everything that merely
    passes the bytes on — to a temp file, to an HTTP body, to ffmpeg — should call
    :func:`download_to_temp` instead, because this function's peak cost is the object's full size
    and the largest live object in this deployment is 668.44 MiB against a 1 GiB box
    (MEASURED, docs/SCALABILITY.md §5.1).

    **AND GATE IT ON :func:`head_object` FIRST.** Nothing here bounds what it will allocate: an
    object larger than the box has memory for is read until the allocation fails, and on a
    single-worker uvicorn that takes every in-flight request with it.
    """
    settings = get_settings()
    response = _client().get_object(Bucket=settings.aws_s3_bucket, Key=object_key)
    try:
        return response["Body"].read()
    finally:
        response["Body"].close()


@dataclass(frozen=True, slots=True)
class ObjectHead:
    """What ``HEAD`` says about a stored object: its real size, and how it is typed."""

    size_bytes: int
    mime_type: str | None = None
    etag: str | None = None


class ObjectTooLarge(Exception):
    """A stored object is bigger than the caller is willing to bring into this process.

    Carries both numbers so the caller can say which and by how much. Raised BEFORE any bytes move
    when :func:`head_object` answered, and from inside the transfer when it did not.
    """

    def __init__(self, object_key: str, size_bytes: int, limit_bytes: int) -> None:
        self.object_key = object_key
        self.size_bytes = size_bytes
        self.limit_bytes = limit_bytes
        super().__init__(
            f"{object_key} is {size_bytes} bytes, over the {limit_bytes}-byte limit for this read"
        )


def head_object(object_key: str) -> ObjectHead | None:
    """The object's REAL length without fetching a byte of it, or ``None`` when storage won't say.

    **THIS IS THE ONLY NUMBER IN THIS SYSTEM THAT IS A FACT.** ``MediaFile.sizeBytes`` is whatever
    the client declared at ``POST /media/complete``: the schema bounds it only from below
    (``Field(gt=0)``), it is stored verbatim, and nothing reconciles it against the stored object.
    Nor does the upload signature bound the body — ``presign_put_url`` deliberately signs no
    ``content-length-range``, and its docstring records why that must stay so. So an account could
    presign an upload declaring ``sizeBytes: 1024``, PUT 1.5 GB to the returned URL and complete it;
    every cap compared against the column would wave it through. This call is how a caller checks.

    **``None`` MEANS "UNKNOWN", NEVER "SMALL".** A missing key, a permission the API's own
    credentials lack, a custom endpoint that does not implement ``HEAD`` — boto3 raises a different
    class for each and the answer to all of them is the same: this pre-check could not be made.
    Turning that into an exception would add a new failure mode to a path whose real fetch already
    handles failing, so the caller is told nothing rather than told a lie, and must then fall back
    to a bound it can enforce itself (``download_to_temp``'s ``max_bytes``, which needs no HEAD).
    """
    settings = get_settings()
    try:
        response = _client().head_object(Bucket=settings.aws_s3_bucket, Key=object_key)
    except Exception:  # noqa: BLE001 - see above: every failure class means the same thing here
        logger.info("head_object could not size %s; size-gated callers will fall back", object_key)
        return None
    try:
        size = int(response.get("ContentLength") or 0)
    except (TypeError, ValueError):
        return None
    return ObjectHead(
        size_bytes=size,
        mime_type=response.get("ContentType") or None,
        etag=(response.get("ETag") or "").strip('"') or None,
    )


class _BoundedWriter:
    """A write-through proxy over the temp file that refuses to hold more than *limit* bytes.

    **BELT AND BRACES BEHIND :func:`head_object`, AND IT IS NOT REDUNDANT.** ``head_object``
    answers ``None`` on a custom endpoint that does not implement ``HEAD``, and even when it
    answers, the object it described can be replaced between the HEAD and the GET. This measures what
    actually lands and raises the moment the file would go over, so the bound holds without a HEAD at
    all — which is what lets ``max_bytes`` be the caller's real guarantee rather than a hint.

    ``download_fileobj`` writes a ranged multipart download at offsets, so ``seek``/``tell`` are
    proxied and each write is measured under a lock. WHAT IS BOUNDED IS THE OFFSET THE WRITE REACHES
    — ``tell() + len(data)`` — AND NOT A RUNNING SUM OF BYTES WRITTEN, and the difference is not
    academic. ``s3transfer`` retries a part by re-writing it from its own beginning:
    ``GetObjectTask._main`` (s3transfer 0.19.2, ``download.py``) resets ``current_index =
    start_index`` at the top of every attempt and ``continue``s the attempt loop on a retryable
    error — connection reset, read timeout, ``IncompleteReadError`` and friends, up to
    ``num_download_attempts`` (5 by default, and this module passes no ``TransferConfig``) — which
    is why its retry arm reports the NEGATIVE progress delta ``start_index - current_index``. A
    summed total counts those replayed bytes twice and refuses an object comfortably inside the
    ceiling: 26 MiB under a 32 MiB cap plus one retried 8 MiB part sums to 34 MiB. A file is never
    longer than the furthest offset any write reached, so refusing the first write that reaches past
    the limit is exactly the bound — whatever order the ranges arrive in, and however often one of
    them is retried.

    **``seekable`` IS DECLARED EXPLICITLY AND ``__getattr__`` FORWARDS THE REST, both deliberately.**
    ``s3transfer`` picks its output manager by asking ``seekable(fileobj)``, which prefers a
    ``seekable()`` method and only falls back to probing ``seek``/``tell`` when there is none —
    answering it directly is what keeps this proxy on the same (seekable, ranged) code path the bare
    temp file would have taken, rather than on the non-seekable one by accident. Everything else
    ``s3transfer`` might reach for goes straight through to the real file, so this class does not
    have to enumerate a private library's file protocol correctly to avoid breaking every download.
    """

    def __init__(self, handle: Any, object_key: str, limit: int) -> None:
        self._handle = handle
        self._object_key = object_key
        self._limit = limit
        self._lock = threading.Lock()

    def write(self, data: Any) -> int:
        # Read-the-position / check / write is ONE critical section. The offset a write lands at is
        # whatever the preceding `seek` left behind, so measuring it has to happen with no other
        # writer able to move it in between. (`s3transfer` runs a single IO thread today —
        # `manager.py` builds `_io_executor` with `max_num_threads=1` — but a proxy over somebody
        # else's file protocol must not depend on that staying true.)
        with self._lock:
            reach = self._handle.tell() + len(data)
            if reach > self._limit:
                raise ObjectTooLarge(self._object_key, reach, self._limit)
            return self._handle.write(data)

    def seek(self, *args: Any, **kwargs: Any) -> int:
        with self._lock:
            return self._handle.seek(*args, **kwargs)

    def tell(self) -> int:
        with self._lock:
            return self._handle.tell()

    def seekable(self) -> bool:
        return self._handle.seekable()

    def flush(self) -> None:
        self._handle.flush()

    def __getattr__(self, name: str) -> Any:
        # Only reached for names this class does not define — `_handle` and friends are set in
        # `__init__` and live in `__dict__`, so this cannot recurse.
        return getattr(self._handle, name)


def download_to_temp(
    object_key: str, *, suffix: str = "", max_bytes: int | None = None
) -> str:
    """Stream one stored object to a temp file and return its path. **The caller owns that file.**

    THE POINT OF THIS FUNCTION IS THAT THE OBJECT NEVER BECOMES A ``bytes``. ``get_object_bytes``
    allocates the object's full size in the heap and holds it for as long as the caller does;
    boto3's managed transfer moves it in ranged chunks straight onto disk, so peak heap is a chunk
    and the box trades RAM it does not have for disk it does. That is the whole of
    docs/SCALABILITY.md §5.1 fix 1.

    *max_bytes* is enforced twice and neither check is decorative: :func:`head_object` refuses an
    oversized object before a byte moves, and :class:`_BoundedWriter` refuses one that storage
    would not size (or that changed underneath the HEAD) as it arrives. Either way the caller gets
    :class:`ObjectTooLarge` with both numbers in it, and no partial file is left behind — a refusal
    that leaked a half-written temp file would fill the disk it was protecting.

    **THE CALLER MUST DELETE THE PATH**, with :func:`discard_temp` in a ``finally``. The file is
    created with ``delete=False`` because the whole point is to hand it to something else — pydub,
    ``open()`` for an upload body, a ``FileResponse`` — that needs it to still exist after this
    function returns.
    """
    settings = get_settings()
    if max_bytes is not None:
        head = head_object(object_key)
        if head is not None and head.size_bytes > max_bytes:
            raise ObjectTooLarge(object_key, head.size_bytes, max_bytes)

    handle = tempfile.NamedTemporaryFile(delete=False, suffix=suffix, prefix="s3obj-")  # noqa: SIM115
    path = handle.name
    try:
        target: Any = handle if max_bytes is None else _BoundedWriter(handle, object_key, max_bytes)
        _client().download_fileobj(settings.aws_s3_bucket, object_key, target)
        handle.flush()
    except BaseException:
        # Every exit that is not a completed download leaves nothing behind, ObjectTooLarge and a
        # cancellation included: a temp file nobody holds a path to is a leak the process cannot
        # find again, and this runs on a box whose disk is the thing being protected.
        handle.close()
        discard_temp(path)
        raise
    handle.close()
    return path


def discard_temp(path: str | None) -> None:
    """Delete a :func:`download_to_temp` file. Safe to call twice, and on ``None``.

    Swallows the removal failure deliberately: this is what a ``finally`` calls after the real work
    has already succeeded or already failed, and letting an unlink error out of it would replace a
    useful outcome (or a useful exception) with a message about a temp file.
    """
    if not path:
        return
    try:
        os.unlink(path)
    except FileNotFoundError:
        # Already gone, which is the whole of "safe to call twice" and not worth a log line.
        return
    except OSError as exc:  # see the docstring: a leaked temp file must not fail a request
        logger.warning("Could not remove the temporary object file %s: %s", path, exc)


def delete_object(object_key: str) -> None:
    """Remove a single object. Used to clean up staged uploads that were cancelled before save."""
    settings = get_settings()
    _client().delete_object(Bucket=settings.aws_s3_bucket, Key=object_key)
