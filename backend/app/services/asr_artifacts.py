"""The offline speech model as bytes THIS deployment serves, and the gate in front of them.

A field handset cannot fetch the model from where it is published. ``ai4bharat`` repos are
``gated: auto`` and an unauthenticated ``HEAD`` of a weight file answers **401**; the k2-fsa release
assets are open but are a ``.tar.bz2``, which nothing in the APK can open
(``DwAsrContainerFormat.TAR_BZ2``, ``supported = false``). Meanwhile every designer is already
authenticated against THIS API, and ``adb reverse tcp:8000 tcp:8000`` on the fleet's SM-M325F proves
the handset reaches it. So the deployment serves the artifact: one copy is prepared once by whoever
publishes it, and no designer is ever shown a HuggingFace token prompt.

================================================================================================
WHERE THE BYTES LIVE — A DIRECTORY THE API CAN READ, AND NOT OBJECT STORAGE
================================================================================================

``ASR_MODEL_DIR`` names a directory; each artifact is a subdirectory named exactly by its id, holding
the artifact's files under their exact names. Nothing in this module writes to it. How the bytes
ARRIVE in that directory is an operator step, documented in ``docs/ASR-MODEL-HOSTING.md`` — a bind
mount in compose, an initContainer that pulls from the same bucket the APK lives in, or a file copied
by hand. **A 365 MB binary does not belong in git and is not in it.**

**The obvious alternative was object storage, and it was declined for three reasons, in order of
weight.** The APK is served that way already (``app_release.py`` 307-redirects to a presigned S3
URL), so this is a departure from the house idiom and needs the argument written down:

1. **The digest could then only be a stored copy, and a stored copy can drift.** The whole point of
   this endpoint is that what it publishes as the SHA-256 is computed from the bytes it would serve.
   Hashing a 365 MB S3 object per manifest read is not affordable, so the digest would have to live
   in a column beside the key — a second copy of a fact, which is exactly the shape that goes wrong
   quietly. Against a local file the digest is computed from the file and memoised on
   ``(size, mtime, ctime)``.

   **THIS USED TO CLAIM "so there IS no second copy to disagree", AND THAT WAS FALSE.** The memo IS
   a second copy; what makes it different from the column is not that it does not exist but that its
   invalidation key is taken off the same file, so it cannot survive a change the filesystem records.
   The distinction matters because it is exactly as strong as the key, and the key was measured on
   2026-08-13 to be weaker than :func:`file_digest` claimed on one platform. The correction, the
   reproduction and the publishing rule that closes it are in that function's docstring; a reader who
   takes "no second copy" literally will not go looking for it.
2. **A redirect cannot answer "the file is not there" honestly.** Requirement: a clear 503, never a
   200 with a truncated body. A 307 to storage hands the client whatever the bucket says — an XML
   ``NoSuchKey`` with a 404, or worse a 200 of a half-uploaded object. Serving the bytes ourselves
   means the size and the digest are checked BEFORE the first byte of the body is written.
3. **On the fleet, only the API is reachable from the handset.** ``adb reverse`` forwards port 8000
   and nothing else, so a redirect to ``http://minio:9000`` resolves to nothing on the phone that
   this feature has to be proved on. A route that cannot be exercised end to end on the one device
   in the room is not a route anybody has tested.

The cost of the choice, stated rather than hidden: the bytes cross the API process, so a download
occupies a worker for its duration and is billed to the pod's egress twice. Acceptable at this
scale — the artifact is fetched once per handset per model version by a fleet of tens — and the
answer if it ever stops being acceptable is a CDN in front of these paths, which is safe precisely
because they are immutable (§1 of ``docs/ASR-RUNTIME-DOWNLOAD-CONTRACT.md``).

================================================================================================
WHAT THE PUBLISHED DIGEST IS, AND WHAT IT IS EMPHATICALLY NOT
================================================================================================

``docs/ASR-RUNTIME-DOWNLOAD-CONTRACT.md`` §1 says there is no endpoint, and says why: *"a digest
supplied by the same host that supplies the bytes verifies the file against its own sender"*. That
argument is correct and this module does not weaken it.

**The value the handset trusts is still the constant compiled into the APK** —
``DwAsrModelFile.sha256`` in ``android/…/data/DwAsrModel.kt``, signed with the APK. The digest
published here is a different thing with three uses, none of which is being the trust anchor:

* **It is the server's own tripwire.** :data:`ASR_MODEL_ARTIFACTS` pins the digest the release
  builder measured. This module computes the digest of the file on disk and REFUSES TO SERVE A BYTE
  unless the two match — so a truncated copy, a half-finished upload or a substituted graph is a 503
  naming what is wrong, not a 365 MB download that fails on the phone an hour later.
* **It lets a client fail before spending the bytes.** A phone that compares the manifest against its
  own pinned constant learns of a mismatch in one JSON read instead of after a fetch on a prepaid
  bundle.
* **It is what an operator audits the directory with**, without hashing anything by hand.

So the manifest is advisory in exactly the direction that matters: it can only ever cause a fetch to
be REFUSED, never to be accepted. A hostile server that lied in the manifest would still be caught by
the APK's own constant, and a hostile server that told the truth in the manifest while serving other
bytes would be caught by the same constant. Nothing here is load-bearing for integrity, and the day
somebody makes it load-bearing — a client that verifies against the manifest instead of its own
constant — that document's central argument really is void.

================================================================================================
WHY THE CATALOGUE IS CODE AND NOT A TABLE
================================================================================================

An artifact's LANGUAGES are the one field here that cannot be typed in by an operator. ``hi-IN``
below is not a description of the model, it is a measurement: what the fleet's own SM-M325F actually
transcribed, scored, on the handset — and ``DwDeviceTier.kt``'s catalogue keeps Odia OUT of that list
at a measured 53.3% WER even though the model plainly emits Odia script. A table would let somebody
publish "this file does Odia" because the model card said so, and a designer in Odisha who installs
365 MB on the strength of that row is the precise failure the whole feature exists to prevent.

Consequence, and it is the same consequence §1 of that document already accepted: **a new artifact
needs a release, of the server and of the app.** The app has to pin the digests anyway, so there was
never a version of this that a server-side row could ship on its own.
"""

import asyncio
import hashlib
import logging
import os
import stat as stat_module
from collections import OrderedDict
from dataclasses import dataclass
from enum import Enum
from pathlib import Path

from app.core.config import get_settings

logger = logging.getLogger(__name__)

#: Read size for the digest walk. 1 MiB: large enough that a 365 MB file is 349 reads rather than
#: 5,578, small enough that eight concurrent hashes are 8 MiB of buffers and not 800.
_DIGEST_CHUNK_BYTES = 1024 * 1024

#: Memoised digests, keyed on identity-of-bytes and bounded. Small on purpose — a handful of
#: artifacts is a real deployment and a hundred is a leak (``deps._USER_CACHE`` for the same rule).
_DIGEST_CACHE_MAX_ENTRIES = 64


# =================================================================================================
# The catalogue
# =================================================================================================


def _is_sha256(value: str) -> bool:
    """64 lower-case hex characters, and nothing else. Mirrors ``dwAsrIsSha256`` in the app."""
    return len(value) == 64 and all(c in "0123456789abcdef" for c in value)


def _is_bare_name(value: str) -> bool:
    """A single path segment: no separator, no ``..``, not blank, not a dot entry.

    Both the artifact id and every file name are joined onto a directory this process can read, so
    each is checked to be a bare segment rather than trusted for being a compiled constant. The
    Android side carries the identical check on the identical strings and says why: an entry like
    ``../databases/workshop.db`` would address a file this feature does not own.
    """
    if not value or value in {".", ".."} or ".." in value:
        return False
    return not ({"/", "\\"} & set(value))


@dataclass(frozen=True)
class AsrArtifactFile:
    """One file of one artifact: its name, the digest measured off it, and its size."""

    #: e.g. ``model.int8.onnx``. A bare file name, never a path.
    file_name: str
    #: Lower-case hex SHA-256 of the file the release builder published. The tripwire, not the
    #: value the handset trusts — see this module's docstring.
    sha256: str
    #: Its exact size in bytes.
    bytes: int
    #: ``application/octet-stream`` FOR BOTH FILES INCLUDING ``tokens.txt``, deliberately. A token
    #: table sent as ``text/plain`` is inside ``main._COMPRESSIBLE_TYPES``, so a gzip layer would
    #: rewrite the framing of a range response, and any transcoding proxy that "fixes" a charset
    #: changes the bytes and therefore the digest. The client writes bytes to disk and hashes them;
    #: it never reads this header.
    media_type: str = "application/octet-stream"

    def __post_init__(self) -> None:
        if not _is_bare_name(self.file_name):
            raise ValueError(
                f"an artifact file needs a bare file name: {self.file_name!r} is not one"
            )
        if not _is_sha256(self.sha256):
            raise ValueError(
                f"{self.file_name}: an artifact file needs the full 64-character lower-case hex "
                "SHA-256 of the exact file being published. A blank or short digest would make the "
                "verification below vacuous, and it is the only thing standing between a designer "
                "and a truncated 365 MB download."
            )
        if self.bytes <= 0:
            raise ValueError(f"{self.file_name}: an artifact file needs its real size in bytes")


@dataclass(frozen=True)
class AsrArtifact:
    """One published model: the files it is made of, and the languages it was MEASURED to serve."""

    #: Also the directory name under ``ASR_MODEL_DIR``, and the ``modelId`` the app pins.
    artifact_id: str
    #: The upstream release this export came out of. The id is the real version key — it carries the
    #: date and the path is immutable per version — and this is the human-readable half of it.
    version: str
    #: ``int8``, ``fp32``, … What the weights are, because it decides whether a handset can load it.
    quantisation: str
    #: BCP-47 tags this artifact was MEASURED to transcribe usably, on a handset. Never a claim.
    languages: tuple[str, ...]
    #: What was measured and what was only claimed, in prose, including what was measured and
    #: REJECTED. A designer who reads "nothing hears Odia" goes looking for a model that has already
    #: been found and rejected; this field is what stops that.
    language_note: str
    #: The upstream artifact string, for tracing a defect to a release rather than to "the model".
    upstream_version: str
    #: How the publisher obtained it, in sentences. The only record of what they believed they were
    #: publishing; the digest proves only that it is the file they pinned.
    provenance: str
    files: tuple[AsrArtifactFile, ...]

    def __post_init__(self) -> None:
        if not _is_bare_name(self.artifact_id):
            raise ValueError(
                f"an artifact id is also a directory name: {self.artifact_id!r} is not a bare name"
            )
        if not self.files:
            raise ValueError(f"{self.artifact_id}: an artifact with no files is not an artifact")
        names = [f.file_name for f in self.files]
        if len(set(names)) != len(names):
            raise ValueError(f"{self.artifact_id}: two files with one name — {sorted(names)}")
        if not self.languages or not all(tag.strip() for tag in self.languages):
            raise ValueError(
                f"{self.artifact_id}: an artifact must name the languages it was measured to "
                "serve. An empty list would publish a several-hundred-megabyte download with "
                "nothing said about what it can hear, which is the one thing this feature exists "
                "to refuse."
            )
        required = ("version", "quantisation", "language_note", "upstream_version", "provenance")
        for field_name in required:
            if not str(getattr(self, field_name)).strip():
                raise ValueError(f"{self.artifact_id}: {field_name} is required and is blank")

    @property
    def total_bytes(self) -> int:
        """What a designer spends to install this artifact whole."""
        return sum(f.bytes for f in self.files)

    def file(self, file_name: str) -> AsrArtifactFile | None:
        """The named file of this artifact, or None. **The only lookup**, so a name that came off a
        URL is compared against the catalogue and never joined onto a path."""
        return next((f for f in self.files if f.file_name == file_name), None)


#: THE ARTIFACTS THIS DEPLOYMENT PUBLISHES. Every field is a record of something measured.
#:
#: One row, and it is the artifact that actually exists: the two files
#: ``docs/ASR-MODEL-SIDELOAD.md`` currently tells an operator to ``adb push`` over a cable. Those
#: bytes have been on the fleet's SM-M325F, hashed there, and used to transcribe real Odia and Hindi
#: speech; the digests and sizes below are the ones ``DW_ASR_MODELS`` pins in the APK, and
#: ``tests/test_asr_model_download.py`` reads that Kotlin file and asserts they still agree.
#:
#: **IndicConformer is not here yet, and the reason is memory, not availability.** The official
#: ``ai4bharat/indic-conformer-600m-multilingual`` ONNX export loads on the sherpa-onnx 1.13.5
#: vendored in this APK and is the more accurate model by a wide margin in Odia (WER 52.8% → 13.9%
#: on identical references), but its encoder is 2,428,824,576 bytes of fp32 external weight data
#: against the handset's measured ``MemAvailable`` of 1,340,412 kB — it cannot load at all.
#:
#: **"int8 is unmeasured" WAS TRUE WHEN THIS WAS WRITTEN AND IS NOT ANY MORE. It was measured on
#: 2026-08-13 and it does not work**, which closes the question this comment left open and closes it
#: the unwelcome way. ``onnxruntime.quantization.quantize_dynamic`` was run twice over the same
#: Odia-sliced graph, on a quiet box, and both products load and then decode nothing usable:
#:
#: ===============================  ==============  ====================================================
#: op selection                     bytes           what it decodes on 3 FLEURS Odia utterances
#: ===============================  ==============  ====================================================
#: default (Conv + MatMul + …)      654,790,526     the EMPTY STRING, all three. CER/WER 100%
#: ``op_types_to_quantize=MatMul``  883,021,360      one character, ``ପ``, all three. CER 99.4, WER 100%
#: fp32 reference                   2,432,855,148   CER 5.1, WER 16.7
#: ===============================  ==============  ====================================================
#:
#: The first run logged ``Inference failed or unsupported type to quantize`` for every depthwise-conv
#: slice in the Conformer's convolution modules, which is why the second excluded Conv — and the
#: second is both LARGER and still broken. Dynamic int8 is therefore not the route to making this
#: model fit. ~~and there is no third quantisation to try that would not be a research task.~~
#:
#: **THAT LAST CLAUSE IS RETRACTED, 2026-08-13 06:15.** It generalised from one script on one graph to
#: the format, and the format is fine: ``OpenVoiceOS/ai4bharat-indicconformer-hi-onnx``
#: ``model.int8.onnx``, **137,677,313 bytes**, an int8 export of AI4Bharat's own 120M Hindi
#: checkpoint, loads on sherpa-onnx 1.13.5 and scores **CER 5.6 / WER 19.8** on the three Hindi FLEURS
#: utterances the 600M fp32 scores 6.9 / 20.9 on — better, at 1/18th the file — and costs
#: **538,144,768 bytes peak VmHWM** on the fleet's SM-M325F. What is refused is still what was always
#: refused here: **a third-party repackage is not a row this server publishes**, and that reason is a
#: supply-chain decision rather than a measurement. See ``docs/ASR-RUNTIME-MEASUREMENT.md`` §6.
#:
#: **So publishing a row for the 600M would be publishing a download that cannot run, in any
#: quantisation this deployment can produce.** The route to a shippable IndicConformer is the 120M
#: per-language checkpoint exported through NeMo's own exporter — not this repo's arithmetic and not a
#: third-party repackage, which was tried: ``jeswinjestin/sherpa-onnx-nemo-ctc-indicconformer-
#: malayalam`` is a genuine 493,060,445-byte AI4Bharat IndicConformer in exactly this engine's layout,
#: and handed Odia and Hindi audio it answers in **Malayalam script** at 100% WER. Its head is
#: Malayalam-locked whatever its 5,633-line ``tokens.txt`` spans.
ASR_MODEL_ARTIFACTS: tuple[AsrArtifact, ...] = (
    AsrArtifact(
        artifact_id="sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12",
        version="2025-11-12",
        quantisation="int8",
        # MEASURED ON THE HANDSET, and this list is deliberately shorter than the model's ability.
        # `DW_TIER1_CATALOGUE` in DwDeviceTier.kt carries the identical single tag and argues it at
        # length: Odia is emitted correctly-scripted and scored 53.3% WER on FLEURS studio speech,
        # which is an easier test than a courtyard, so it is measured, rejected, and said so.
        languages=("hi-IN",),
        language_note=(
            "MEASURED ON THE FLEET'S OWN SM-M325F, not claimed: Hindi 24.2% WER on FLEURS studio "
            "read speech, n=3 — a demonstration rather than an evaluation, and offered only where "
            "the alternative is no dictation at all. Odia was MEASURED AND REJECTED at 53.3% WER "
            "on the same corpus by the same script: the model emits correctly-scripted Odia and "
            "more than half the words are wrong, so it is not a language this artifact serves and "
            "the craft-aware server transcript stays ahead of it. Meta claim 1,600+ languages for "
            "this family; that is a statement about a family, is not verified here, and is not a "
            "property of this file. Every other language is UNMEASURED — not absent and not "
            "present: nobody has asked."
        ),
        upstream_version=(
            "sherpa-onnx asr-models, asset "
            "sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12.tar.bz2"
        ),
        provenance=(
            "Downloaded 2026-08-12 from the k2-fsa/sherpa-onnx GitHub release tag `asr-models` — "
            "the same project and the same release index the engine AAR comes from — and unpacked "
            "locally. The tarball was 292,571,207 bytes and hashed to "
            "cdcd0559c7c73efed54209a926e321afc914d046c5fdbf3665f00dc78180e5ed. It is sherpa-onnx's "
            "own ONNX export of Meta's Omnilingual ASR CTC 300M; it was NOT re-quantised, "
            "re-exported or repacked here. Nothing upstream publishes a signature for it, so this "
            "chain establishes what was published and not who made it."
        ),
        files=(
            AsrArtifactFile(
                file_name="model.int8.onnx",
                sha256="e7c4e54ee4c4c47829cc6667d5d00ed8ea7bef1dcfeef0fce766f77752a2726c",
                bytes=365_352_120,
            ),
            AsrArtifactFile(
                file_name="tokens.txt",
                sha256="a7a044c52cb29cbe8b0dc1953e92cefd4ca16b0ed968177b6beab21f9a7d0b31",
                bytes=86_423,
            ),
        ),
    ),
)


def artifact_by_id(artifact_id: str) -> AsrArtifact | None:
    """The catalogue row with this id, or None. Exact match, never a prefix or a path."""
    return next((a for a in ASR_MODEL_ARTIFACTS if a.artifact_id == artifact_id), None)


# =================================================================================================
# Where the bytes are, and whether they are the right bytes
# =================================================================================================


class ArtifactRefusal(str, Enum):
    """Why an artifact cannot be served. **Every one of these is a 503, never a partial 200.**

    Ordered by what an operator can do about it, and each is a distinct sentence because "the model
    is unavailable" sends whoever administers the deployment looking in the wrong place.
    """

    #: ``ASR_MODEL_DIR`` is unset. Nothing has been published on this deployment at all.
    NO_STORE_CONFIGURED = "NO_STORE_CONFIGURED"
    #: Configured, and the file is not in it. The ordinary "not published yet" answer.
    NOT_ON_DISK = "NOT_ON_DISK"
    #: A directory, a symlink to nothing, a device node. Present is not the same as servable.
    NOT_A_FILE = "NOT_A_FILE"
    #: Permissions, or the volume went away mid-flight.
    UNREADABLE = "UNREADABLE"
    #: **THE TRUNCATED-UPLOAD CASE.** The file is there and is the wrong length.
    WRONG_SIZE = "WRONG_SIZE"
    #: Right length, wrong bytes. The only refusal that costs a full read to reach.
    WRONG_DIGEST = "WRONG_DIGEST"


def store_root() -> Path | None:
    """The directory artifacts are read from, or None when none is configured.

    **The one place the directory is decided.** Blank and whitespace-only are treated as unset:
    ``ASR_MODEL_DIR=""`` in a compose file means "not configured", and resolving it to the process's
    working directory would turn a typo into "publish whatever happens to be next to the API".
    """
    raw = (getattr(get_settings(), "asr_model_dir", None) or "").strip()
    return Path(raw) if raw else None


def artifact_dir(artifact: AsrArtifact) -> Path | None:
    """Where this artifact's files are expected to sit, or None when no store is configured."""
    root = store_root()
    return None if root is None else root / artifact.artifact_id


# ---------------------------------------------------------------------------------------------
# The digest, computed from the bytes and memoised on the identity of those bytes
# ---------------------------------------------------------------------------------------------

#: ``(path, size, mtime_ns, ctime_ns) -> digest``. Insertion-ordered so the oldest entry evicts.
_DIGEST_CACHE: "OrderedDict[tuple[str, int, int, int], str]" = OrderedDict()


def clear_digest_cache() -> None:
    """Forget every memoised digest. For tests, and for an operator's ``SIGHUP`` equivalent.

    Exists for the reason ``deps.clear_user_cache`` exists: a memo keyed on filesystem metadata is
    only as fresh as that metadata's resolution, and a test that rewrites a file twice inside one
    clock tick needs a way to say so out loud rather than reading a stale answer and passing.
    """
    _DIGEST_CACHE.clear()


def _sha256_of_file(path: Path) -> tuple[str, int]:
    """``(digest, bytes read)`` for the file at ``path``. Blocking; always called in a thread.

    Returns the count actually read as well as the digest, so the caller can compare it against the
    ``stat`` the framing will use. A file that shrank between the stat and the read is the one case
    where a correct-looking ``Content-Length`` would front a short body, and this is what catches it.
    """
    digest = hashlib.sha256()
    read = 0
    with path.open("rb") as handle:
        while chunk := handle.read(_DIGEST_CHUNK_BYTES):
            digest.update(chunk)
            read += len(chunk)
    return digest.hexdigest(), read


async def file_digest(path: Path, stat: os.stat_result) -> tuple[str, int]:
    """The SHA-256 of the bytes on disk, ``(digest, bytes read)``, memoised on those bytes' identity.

    **The digest is derived from the file rather than stored beside it, which is the whole design.**
    It is recomputed on every change to the file that the filesystem records in the memo key below,
    so what the manifest publishes and what the bytes route sends come from one reading of one file.

    The memo key is ``(path, st_size, st_mtime_ns, st_ctime_ns)``. All three metadata fields, not
    just mtime, because the resolutions differ by platform — and the platforms differ by more than
    resolution, which the sentence that used to be here got wrong:

    * **POSIX: ``st_ctime`` is the inode CHANGE time and userspace cannot set it.** ``utimensat``
      moves mtime and atime and bumps ctime to now as a side effect, so ``cp -p``, ``rsync -t``,
      ``tar -xp`` and a restored backup all move the key even though they preserve mtime. MEASURED in
      the ``design-workshop-postgres`` container on 2026-08-13: an in-place same-size write moved
      both stamps, and ``touch -m`` then restoring mtime left ctime at the write's time. The memo is
      sound here, which is what matters, because the deployment is Linux.
    * **WINDOWS: ``st_ctime`` IS THE CREATION TIME**, not a change time — MEASURED on CPython 3.14.6,
      ``st_ctime_ns == st_birthtime_ns`` and an in-place write does not move it. So an in-place
      same-size overwrite followed by ``os.utime`` restoring mtime reproduces this key EXACTLY, the
      memo answers with the old digest, and the endpoint publishes a SHA-256 it did not compute from
      the bytes it then serves. **Reproduced over HTTP on 2026-08-13**: ``tokens.txt`` overwritten in
      place, mtime restored, manifest still ``available: true`` with ``sha256``
      ``a7a044c52cb29cbe8b0dc1953e92cefd4ca16b0ed968177b6beab21f9a7d0b31``, ``ETag`` the same, and the
      86,423 bytes served hashing to ``407320d18b0502d0c01bb882946ad5351e039c8348aa4414bf340afd38cb88d6``.
      The claim it replaces — "the residual window is a same-size overwrite completed inside one
      filesystem timestamp tick" — was narrower than the truth: on Windows there is no tick
      constraint at all.

    **THE PUBLISHING RULE THAT CLOSES IT ON BOTH PLATFORMS, and the reason it is a rule rather than a
    suggestion: write the new bytes to a temporary name in the same directory and RENAME it over the
    old one. Never overwrite an artifact file in place.** A rename moves the key on both — measured
    the same day on Windows, where an ``os.replace`` of a same-size file whose mtime was deliberately
    preserved still produced a new creation time (no NTFS tunnelling at a 1.2 s gap). It is also the
    only way to publish that never exposes a half-written file to a reader, so it is the right
    procedure for a second reason. ``docs/ASR-MODEL-HOSTING.md`` states it as the operator step.

    :func:`clear_digest_cache` remains the explicit way to say "I did overwrite in place", and the
    handset verifies against the digest compiled into its own APK regardless — which is why the
    reproduction above is a defeated tripwire and not a way to install substituted bytes.

    Off the event loop, for ``datasets._stream_rows``' reason: this is 365 MB of pure CPU on a
    single-worker web process, and run inline it would queue every other request in the app —
    including a designer's stage save from the field — behind one download's first request.
    """
    key = (str(path), stat.st_size, stat.st_mtime_ns, getattr(stat, "st_ctime_ns", 0))
    cached = _DIGEST_CACHE.get(key)
    if cached is not None:
        _DIGEST_CACHE.move_to_end(key)
        return cached, stat.st_size
    digest, read = await asyncio.to_thread(_sha256_of_file, path)
    if read == stat.st_size:
        # Only a read that matched the stat is worth remembering: a short read means the file was
        # moving under us, and memoising the digest of a moment would make the next request agree
        # with it.
        _DIGEST_CACHE[key] = digest
        while len(_DIGEST_CACHE) > _DIGEST_CACHE_MAX_ENTRIES:
            _DIGEST_CACHE.popitem(last=False)
    return digest, read


@dataclass(frozen=True)
class FileVerdict:
    """What this deployment can say about one file of one artifact, right now."""

    artifact: AsrArtifact
    spec: AsrArtifactFile
    #: Where it was looked for. None only when no store is configured.
    path: Path | None = None
    #: The ``stat`` the digest was taken against AND the one the response is framed from. One stat,
    #: so ``Content-Length``, the range arithmetic and the verified digest cannot describe three
    #: different versions of the file.
    stat: os.stat_result | None = None
    #: **Computed from the bytes on disk**, never read from the catalogue. None unless it was taken.
    sha256: str | None = None
    refusal: ArtifactRefusal | None = None
    #: One sentence, for an operator. **Names no filesystem path** — that goes to the log, because
    #: this string reaches a designer's phone.
    detail: str = ""

    @property
    def ready(self) -> bool:
        return self.refusal is None


@dataclass(frozen=True)
class ArtifactVerdict:
    """The same, for a whole artifact. Ready only when EVERY file is ready."""

    artifact: AsrArtifact
    files: tuple[FileVerdict, ...]

    @property
    def ready(self) -> bool:
        return all(f.ready for f in self.files)

    @property
    def first_refusal(self) -> FileVerdict | None:
        """The file that stops this artifact being installable, or None.

        The FIRST one rather than a list, for ``dwAsrOffer``'s reason: the useful sentence is the one
        that says what to do next, and six copies of "not published yet" is not six problems.
        """
        return next((f for f in self.files if not f.ready), None)

    @property
    def total_bytes(self) -> int | None:
        """The size of the artifact as it sits on this deployment's disk, or None unless ready.

        Summed from the ``stat`` results, not from the catalogue, so what a designer is told the
        download costs is what the download will cost.
        """
        if not self.ready:
            return None
        return sum(f.stat.st_size for f in self.files if f.stat is not None)


async def verify_file(artifact: AsrArtifact, spec: AsrArtifactFile) -> FileVerdict:
    """Look for one file and decide whether a single byte of it may be sent.

    THE ORDER IS THE POINT. Cheap and specific first, so the common answers cost nothing and the
    expensive one is reached only by a file that is present and the right length:

    1. no store configured, 2. absent, 3. not a regular file, 4. **wrong size** — which is the
    truncated-upload case and is caught by one ``stat``, 5. wrong digest, which costs a full read.

    Nothing below returns a servable verdict without having hashed the file in this process and
    matched it against the published digest, so there is no path from a URL to
    ``send(http.response.body)`` that skips the check.
    """
    root_dir = artifact_dir(artifact)
    if root_dir is None:
        return FileVerdict(
            artifact=artifact,
            spec=spec,
            refusal=ArtifactRefusal.NO_STORE_CONFIGURED,
            detail=(
                "This deployment is not configured to serve speech models: ASR_MODEL_DIR is unset. "
                "Whoever administers it has to publish the artifact first."
            ),
        )

    path = root_dir / spec.file_name
    try:
        stat = await asyncio.to_thread(os.stat, path)
    except OSError as exc:
        # NOT a blind except: `stat` fails for absence, for permissions and for a volume that went
        # away, and the three are different sentences to an operator. FileNotFoundError is the
        # ordinary "nobody has published it yet" case and is not logged as a fault.
        if isinstance(exc, FileNotFoundError):
            return FileVerdict(
                artifact=artifact,
                spec=spec,
                path=path,
                refusal=ArtifactRefusal.NOT_ON_DISK,
                detail=(
                    f"{spec.file_name} of the {artifact.artifact_id} speech model has not been "
                    "published to this deployment yet."
                ),
            )
        logger.warning(
            "ASR artifact %s/%s could not be stat'ed", artifact.artifact_id, spec.file_name
        )
        return FileVerdict(
            artifact=artifact,
            spec=spec,
            path=path,
            refusal=ArtifactRefusal.UNREADABLE,
            detail=(
                f"{spec.file_name} of the {artifact.artifact_id} speech model is on this "
                "deployment but cannot be read. Whoever administers it should check the volume "
                "and its permissions."
            ),
        )

    # Read off the stat already taken rather than with a second `os.path.isfile`: one syscall instead
    # of two, none of it on the event loop, and no window in which the answer changes between the two.
    if not stat_module.S_ISREG(stat.st_mode):
        return FileVerdict(
            artifact=artifact,
            spec=spec,
            path=path,
            stat=stat,
            refusal=ArtifactRefusal.NOT_A_FILE,
            detail=(
                f"What is published as {spec.file_name} of the {artifact.artifact_id} speech model "
                "is not a regular file."
            ),
        )

    if stat.st_size != spec.bytes:
        # THE TRUNCATED FILE, and it costs one stat to find. A half-finished upload is the single
        # most likely thing to be wrong with this directory, and serving it would hand the phone a
        # 200 with a short body — which is exactly the failure the client cannot distinguish from a
        # dropped connection.
        logger.error(
            "ASR artifact %s/%s is %d bytes on disk, published as %d — refusing to serve it",
            artifact.artifact_id,
            spec.file_name,
            stat.st_size,
            spec.bytes,
        )
        return FileVerdict(
            artifact=artifact,
            spec=spec,
            path=path,
            stat=stat,
            refusal=ArtifactRefusal.WRONG_SIZE,
            detail=(
                f"{spec.file_name} of the {artifact.artifact_id} speech model is "
                f"{stat.st_size:,} bytes on this deployment where the published artifact is "
                f"{spec.bytes:,}. It is being refused rather than served short."
            ),
        )

    try:
        digest, read = await file_digest(path, stat)
    except OSError:
        logger.warning("ASR artifact %s/%s could not be read", artifact.artifact_id, spec.file_name)
        return FileVerdict(
            artifact=artifact,
            spec=spec,
            path=path,
            stat=stat,
            refusal=ArtifactRefusal.UNREADABLE,
            detail=(
                f"{spec.file_name} of the {artifact.artifact_id} speech model is on this "
                "deployment but cannot be read. Whoever administers it should check the volume "
                "and its permissions."
            ),
        )

    if read != stat.st_size or digest != spec.sha256:
        logger.error(
            "ASR artifact %s/%s hashes to %s (%d bytes read), published as %s — refusing to serve it",
            artifact.artifact_id,
            spec.file_name,
            digest,
            read,
            spec.sha256,
        )
        return FileVerdict(
            artifact=artifact,
            spec=spec,
            path=path,
            stat=stat,
            sha256=digest,
            refusal=ArtifactRefusal.WRONG_DIGEST,
            detail=(
                f"{spec.file_name} of the {artifact.artifact_id} speech model is the right length "
                "on this deployment but not the right bytes: it does not hash to the digest the "
                "artifact was published with. It is being refused."
            ),
        )

    return FileVerdict(artifact=artifact, spec=spec, path=path, stat=stat, sha256=digest)


async def verify_artifact(artifact: AsrArtifact) -> ArtifactVerdict:
    """Every file of one artifact, checked. Sequential, not gathered.

    Deliberately one at a time: the expensive branch is a full read of a 365 MB file, and hashing
    two of them at once on a single-worker pod to answer a manifest read is spending the box to
    shave a second off a request nobody is waiting on. The memo makes the second read free anyway.
    """
    verdicts = [await verify_file(artifact, spec) for spec in artifact.files]
    return ArtifactVerdict(artifact=artifact, files=tuple(verdicts))
