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

_DIGEST_CHUNK_BYTES = 1024 * 1024

_DIGEST_CACHE_MAX_ENTRIES = 64



def _is_sha256(value: str) -> bool:
    return len(value) == 64 and all(c in "0123456789abcdef" for c in value)


def _is_bare_name(value: str) -> bool:
    if not value or value in {".", ".."} or ".." in value:
        return False
    return not ({"/", "\\"} & set(value))


@dataclass(frozen=True)
class AsrArtifactFile:
    file_name: str
    sha256: str
    bytes: int
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
    artifact_id: str
    version: str
    quantisation: str
    languages: tuple[str, ...]
    language_note: str
    upstream_version: str
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
        return sum(f.bytes for f in self.files)

    def file(self, file_name: str) -> AsrArtifactFile | None:
        return next((f for f in self.files if f.file_name == file_name), None)


ASR_MODEL_ARTIFACTS: tuple[AsrArtifact, ...] = (
    AsrArtifact(
        artifact_id="sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12",
        version="2025-11-12",
        quantisation="int8",
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
    return next((a for a in ASR_MODEL_ARTIFACTS if a.artifact_id == artifact_id), None)



class ArtifactRefusal(str, Enum):
    NO_STORE_CONFIGURED = "NO_STORE_CONFIGURED"
    NOT_ON_DISK = "NOT_ON_DISK"
    NOT_A_FILE = "NOT_A_FILE"
    UNREADABLE = "UNREADABLE"
    WRONG_SIZE = "WRONG_SIZE"
    WRONG_DIGEST = "WRONG_DIGEST"


def store_root() -> Path | None:
    raw = (getattr(get_settings(), "asr_model_dir", None) or "").strip()
    return Path(raw) if raw else None


def artifact_dir(artifact: AsrArtifact) -> Path | None:
    root = store_root()
    return None if root is None else root / artifact.artifact_id


_DIGEST_CACHE: OrderedDict[tuple[str, int, int, int], str] = OrderedDict()


def clear_digest_cache() -> None:
    _DIGEST_CACHE.clear()


def _sha256_of_file(path: Path) -> tuple[str, int]:
    digest = hashlib.sha256()
    read = 0
    with path.open("rb") as handle:
        while chunk := handle.read(_DIGEST_CHUNK_BYTES):
            digest.update(chunk)
            read += len(chunk)
    return digest.hexdigest(), read


async def file_digest(path: Path, stat: os.stat_result) -> tuple[str, int]:
    key = (str(path), stat.st_size, stat.st_mtime_ns, getattr(stat, "st_ctime_ns", 0))
    cached = _DIGEST_CACHE.get(key)
    if cached is not None:
        _DIGEST_CACHE.move_to_end(key)
        return cached, stat.st_size
    digest, read = await asyncio.to_thread(_sha256_of_file, path)
    if read == stat.st_size:
        _DIGEST_CACHE[key] = digest
        while len(_DIGEST_CACHE) > _DIGEST_CACHE_MAX_ENTRIES:
            _DIGEST_CACHE.popitem(last=False)
    return digest, read


@dataclass(frozen=True)
class FileVerdict:
    artifact: AsrArtifact
    spec: AsrArtifactFile
    path: Path | None = None
    stat: os.stat_result | None = None
    sha256: str | None = None
    refusal: ArtifactRefusal | None = None
    detail: str = ""

    @property
    def ready(self) -> bool:
        return self.refusal is None


@dataclass(frozen=True)
class ArtifactVerdict:
    artifact: AsrArtifact
    files: tuple[FileVerdict, ...]

    @property
    def ready(self) -> bool:
        return all(f.ready for f in self.files)

    @property
    def first_refusal(self) -> FileVerdict | None:
        return next((f for f in self.files if not f.ready), None)

    @property
    def total_bytes(self) -> int | None:
        if not self.ready:
            return None
        return sum(f.stat.st_size for f in self.files if f.stat is not None)


async def verify_file(artifact: AsrArtifact, spec: AsrArtifactFile) -> FileVerdict:
    root_dir = artifact_dir(artifact)
    if root_dir is None:
        return FileVerdict(
            artifact=artifact,
            spec=spec,
            refusal=ArtifactRefusal.NO_STORE_CONFIGURED,
            detail="The offline speech model isn't available to download right now.",
        )

    path = root_dir / spec.file_name
    try:
        stat = await asyncio.to_thread(os.stat, path)
    except OSError as exc:
        if isinstance(exc, FileNotFoundError):
            return FileVerdict(
                artifact=artifact,
                spec=spec,
                path=path,
                refusal=ArtifactRefusal.NOT_ON_DISK,
                detail="The offline speech model isn't available to download right now.",
            )
        logger.warning(
            "ASR artifact %s/%s could not be stat'ed", artifact.artifact_id, spec.file_name
        )
        return FileVerdict(
            artifact=artifact,
            spec=spec,
            path=path,
            refusal=ArtifactRefusal.UNREADABLE,
            detail="The offline speech model can't be downloaded right now. Try again later.",
        )

    if not stat_module.S_ISREG(stat.st_mode):
        return FileVerdict(
            artifact=artifact,
            spec=spec,
            path=path,
            stat=stat,
            refusal=ArtifactRefusal.NOT_A_FILE,
            detail="The offline speech model can't be downloaded right now. Try again later.",
        )

    if stat.st_size != spec.bytes:
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
            detail="The offline speech model can't be downloaded right now. Try again later.",
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
            detail="The offline speech model can't be downloaded right now. Try again later.",
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
            detail="The offline speech model can't be downloaded right now. Try again later.",
        )

    return FileVerdict(artifact=artifact, spec=spec, path=path, stat=stat, sha256=digest)


async def verify_artifact(artifact: AsrArtifact) -> ArtifactVerdict:
    verdicts = [await verify_file(artifact, spec) for spec in artifact.files]
    return ArtifactVerdict(artifact=artifact, files=tuple(verdicts))
