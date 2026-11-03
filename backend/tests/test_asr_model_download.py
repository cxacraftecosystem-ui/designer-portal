import asyncio
import hashlib
import os
import re
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import httpx
import pytest
from fastapi import FastAPI

from app.api.routes import asr_models
from app.core import deps
from app.services import asr_artifacts
from app.services.asr_artifacts import ArtifactRefusal, AsrArtifact, AsrArtifactFile

BACKEND = Path(__file__).resolve().parents[1]
ANDROID_DATA = (
    BACKEND.parent / "android/app/src/main/java/com/designprototype/workshop/data"
)

MODEL_BYTES = bytes(range(256)) * 8 + b"\x99\x17\x03"
TOKENS_BYTES = b"".join(f"tok{i}\n".encode() for i in range(37))


def _sha(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


ARTIFACT_ID = "test-asr-artifact-2026-01-01"
MODEL_NAME = "model.int8.onnx"
TOKENS_NAME = "tokens.txt"


def _test_artifact() -> AsrArtifact:
    return AsrArtifact(
        artifact_id=ARTIFACT_ID,
        version="2026-01-01",
        quantisation="int8",
        languages=("hi-IN",),
        language_note="Synthetic row; measured on nothing and never published.",
        upstream_version="tests/test_asr_model_download.py",
        provenance="Written by this test module. These bytes are not a model.",
        files=(
            AsrArtifactFile(file_name=MODEL_NAME, sha256=_sha(MODEL_BYTES), bytes=len(MODEL_BYTES)),
            AsrArtifactFile(
                file_name=TOKENS_NAME, sha256=_sha(TOKENS_BYTES), bytes=len(TOKENS_BYTES)
            ),
        ),
    )


def _person(role: str = "DESIGNER") -> SimpleNamespace:
    return SimpleNamespace(id=f"user-{role.lower()}", email=f"{role}@example.test", role=role)



@pytest.fixture
def caller() -> dict[str, Any]:
    return {"user": _person("DESIGNER")}


@pytest.fixture
def client(caller: dict[str, Any]) -> httpx.AsyncClient:
    app = FastAPI()
    app.include_router(asr_models.router, prefix="/api")
    app.dependency_overrides[deps.get_current_user] = lambda: caller["user"]
    return httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url="http://asr.test"
    )


@pytest.fixture
def catalogue(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> AsrArtifact:
    artifact = _test_artifact()
    root = tmp_path / "asr-models"
    root.mkdir()
    monkeypatch.setattr(asr_artifacts, "store_root", lambda: root)
    monkeypatch.setattr(asr_artifacts, "ASR_MODEL_ARTIFACTS", (artifact,))
    asr_artifacts.clear_digest_cache()
    yield artifact
    asr_artifacts.clear_digest_cache()


@pytest.fixture
def published(catalogue: AsrArtifact) -> AsrArtifact:
    directory = asr_artifacts.artifact_dir(catalogue)
    assert directory is not None
    directory.mkdir(parents=True, exist_ok=True)
    (directory / MODEL_NAME).write_bytes(MODEL_BYTES)
    (directory / TOKENS_NAME).write_bytes(TOKENS_BYTES)
    return catalogue


def _artifact_of(payload: dict[str, Any]) -> dict[str, Any]:
    assert len(payload["artifacts"]) == 1
    return payload["artifacts"][0]


def _file_of(artifact: dict[str, Any], name: str) -> dict[str, Any]:
    return next(f for f in artifact["files"] if f["fileName"] == name)


def _model_url() -> str:
    return f"/api/asr-models/{ARTIFACT_ID}/files/{MODEL_NAME}"



def test_every_published_artifact_states_what_was_measured_off_it():
    assert asr_artifacts.ASR_MODEL_ARTIFACTS, "no artifact is published at all"
    for artifact in asr_artifacts.ASR_MODEL_ARTIFACTS:
        assert artifact.languages, f"{artifact.artifact_id} names no measured language"
        assert artifact.files
        assert artifact.total_bytes == sum(f.bytes for f in artifact.files)
        for spec in artifact.files:
            assert re.fullmatch(r"[0-9a-f]{64}", spec.sha256)
            assert spec.bytes > 0
            assert "/" not in spec.file_name and "\\" not in spec.file_name
            assert spec.media_type == "application/octet-stream"


@pytest.mark.parametrize(
    "digest", ["", "abc", "E7C4E54EE4C4C47829CC6667D5D00ED8EA7BEF1DCFEEF0FCE766F77752A2726C"]
)
def test_a_file_without_a_full_lower_case_digest_cannot_be_published(digest: str):
    with pytest.raises(ValueError, match="SHA-256"):
        AsrArtifactFile(file_name="model.onnx", sha256=digest, bytes=10)


@pytest.mark.parametrize("name", ["", "..", "../model.onnx", "sub/model.onnx", "sub\\model.onnx"])
def test_a_file_name_that_is_not_a_bare_name_cannot_be_published(name: str):
    with pytest.raises(ValueError, match="bare file name"):
        AsrArtifactFile(file_name=name, sha256=_sha(b"x"), bytes=10)


def test_an_artifact_with_no_measured_language_cannot_be_published():
    with pytest.raises(ValueError, match="languages it was measured"):
        AsrArtifact(
            artifact_id="x",
            version="1",
            quantisation="int8",
            languages=(),
            language_note="n",
            upstream_version="u",
            provenance="p",
            files=(AsrArtifactFile(file_name="m", sha256=_sha(b"x"), bytes=1),),
        )


def test_an_artifact_id_is_a_bare_directory_name():
    with pytest.raises(ValueError, match="bare name"):
        AsrArtifact(
            artifact_id="../etc",
            version="1",
            quantisation="int8",
            languages=("hi-IN",),
            language_note="n",
            upstream_version="u",
            provenance="p",
            files=(AsrArtifactFile(file_name="m", sha256=_sha(b"x"), bytes=1),),
        )



def _kotlin(name: str) -> str:
    path = ANDROID_DATA / name
    if not path.is_file():
        pytest.skip(f"the Android client is not present in this checkout ({name})")
    return path.read_text(encoding="utf-8")


def test_the_apk_pins_the_same_files_this_deployment_serves():
    text = _kotlin("DwAsrModel.kt")
    kotlin_ids = set(re.findall(r'modelId = "([^"]+)"', text))
    kotlin_files = {
        name: (digest, int(size.replace("_", "")))
        for name, digest, size in re.findall(
            r'fileName = "([^"]+)",\s*\n\s*sha256 = "([0-9a-f]{64})",\s*\n\s*bytes = ([0-9_]+)L',
            text,
        )
    }
    assert kotlin_files, "DwAsrModel.kt's catalogue could not be parsed — the format moved"

    for artifact in asr_artifacts.ASR_MODEL_ARTIFACTS:
        assert artifact.artifact_id in kotlin_ids, (
            f"this deployment publishes {artifact.artifact_id} and the APK pins no such modelId, "
            "so every phone in the field would refuse the download"
        )
        for spec in artifact.files:
            assert spec.file_name in kotlin_files, f"{spec.file_name} is not pinned in the APK"
            digest, size = kotlin_files[spec.file_name]
            assert (spec.sha256, spec.bytes) == (digest, size), (
                f"{spec.file_name}: this deployment publishes {spec.sha256} at {spec.bytes} bytes "
                f"and the APK expects {digest} at {size}"
            )


def test_the_apk_and_the_server_agree_on_which_languages_were_measured():
    text = _kotlin("DwDeviceTier.kt")
    listed = re.findall(r'languages = listOf\(([^)]*)\)', text)
    if not listed:
        pytest.skip("DW_TIER1_CATALOGUE declares no languages in this checkout")
    kotlin_tags = {tag for block in listed for tag in re.findall(r'"([^"]+)"', block)}
    for artifact in asr_artifacts.ASR_MODEL_ARTIFACTS:
        assert set(artifact.languages) == kotlin_tags, (
            f"{artifact.artifact_id}: the server says {sorted(artifact.languages)} and the app says "
            f"{sorted(kotlin_tags)}"
        )



async def test_the_manifest_publishes_the_digest_of_the_bytes_on_disk(
    client: httpx.AsyncClient, published: AsrArtifact
):
    async with client as c:
        response = await c.get("/api/asr-models")
    assert response.status_code == 200
    artifact = _artifact_of(response.json())
    assert artifact["available"] is True
    assert artifact["unavailableReason"] is None
    assert artifact["totalBytes"] == len(MODEL_BYTES) + len(TOKENS_BYTES)
    assert artifact["languages"] == ["hi-IN"]
    assert artifact["version"] == "2026-01-01"

    directory = asr_artifacts.artifact_dir(published)
    for name, payload in ((MODEL_NAME, MODEL_BYTES), (TOKENS_NAME, TOKENS_BYTES)):
        entry = _file_of(artifact, name)
        on_disk = (directory / name).read_bytes()
        assert entry["sha256"] == hashlib.sha256(on_disk).hexdigest()
        assert entry["bytes"] == len(payload) == len(on_disk)
        assert entry["available"] is True


async def test_the_published_digest_cannot_drift_from_the_bytes_it_describes(
    client: httpx.AsyncClient, published: AsrArtifact
):
    directory = asr_artifacts.artifact_dir(published)
    substituted = bytes(len(MODEL_BYTES))
    assert len(substituted) == len(MODEL_BYTES) and substituted != MODEL_BYTES
    (directory / MODEL_NAME).write_bytes(substituted)
    asr_artifacts.clear_digest_cache()

    async with client as c:
        manifest = await c.get("/api/asr-models")
        download = await c.get(_model_url())

    artifact = _artifact_of(manifest.json())
    entry = _file_of(artifact, MODEL_NAME)
    assert artifact["available"] is False
    assert artifact["unavailableReason"] == ArtifactRefusal.WRONG_DIGEST.value
    assert entry["sha256"] is None, "the manifest echoed a digest no file on this deployment has"
    assert published.files[0].sha256 not in manifest.text
    assert artifact["totalBytes"] is None

    assert download.status_code == 503
    assert substituted not in download.content


async def test_the_manifest_answers_when_the_bytes_have_not_been_published(
    client: httpx.AsyncClient, catalogue: AsrArtifact
):
    async with client as c:
        response = await c.get("/api/asr-models")
    assert response.status_code == 200
    artifact = _artifact_of(response.json())
    assert artifact["available"] is False
    assert artifact["unavailableReason"] == ArtifactRefusal.NOT_ON_DISK.value
    assert artifact["totalBytes"] is None
    assert all(f["sha256"] is None and f["bytes"] is None for f in artifact["files"])
    assert artifact["languages"] == ["hi-IN"]
    assert artifact["detail"]


async def test_the_manifest_says_so_when_this_deployment_hosts_no_models_at_all(
    client: httpx.AsyncClient, catalogue: AsrArtifact, monkeypatch: pytest.MonkeyPatch
):
    monkeypatch.setattr(asr_artifacts, "store_root", lambda: None)
    async with client as c:
        manifest = await c.get("/api/asr-models")
        download = await c.get(_model_url())
    artifact = _artifact_of(manifest.json())
    assert artifact["unavailableReason"] == ArtifactRefusal.NO_STORE_CONFIGURED.value
    assert download.status_code == 503
    assert download.json()["detail"] == (
        "The offline speech model isn't available to download right now."
    )


async def test_the_manifest_never_hands_out_a_host_it_was_told(
    client: httpx.AsyncClient, published: AsrArtifact
):
    async with client as c:
        response = await c.get("/api/asr-models", headers={"Host": "evil.example"})
    for entry in _artifact_of(response.json())["files"]:
        assert entry["url"].startswith("/api/asr-models/")
        assert "://" not in entry["url"]
        assert "evil.example" not in entry["url"]
    assert _file_of(_artifact_of(response.json()), MODEL_NAME)["url"] == _model_url()


async def test_the_manifest_is_not_cacheable_and_says_what_its_digest_is_for(
    client: httpx.AsyncClient, published: AsrArtifact
):
    async with client as c:
        response = await c.get("/api/asr-models")
    assert "no-store" in response.headers["cache-control"]
    assert "not a signature" in response.json()["digestSource"]


async def test_one_artifact_can_be_read_on_its_own(
    client: httpx.AsyncClient, published: AsrArtifact
):
    async with client as c:
        one = await c.get(f"/api/asr-models/{ARTIFACT_ID}")
        unknown = await c.get("/api/asr-models/no-such-model")
    assert one.status_code == 200
    assert one.json()["artifactId"] == ARTIFACT_ID
    assert one.json()["available"] is True
    assert unknown.status_code == 404



async def test_the_whole_file_arrives_and_hashes_to_the_published_digest(
    client: httpx.AsyncClient, published: AsrArtifact
):
    async with client as c:
        response = await c.get(_model_url())
    assert response.status_code == 200
    assert response.content == MODEL_BYTES
    assert hashlib.sha256(response.content).hexdigest() == published.files[0].sha256
    assert response.headers["content-length"] == str(len(MODEL_BYTES))
    assert response.headers["accept-ranges"] == "bytes"
    assert response.headers["content-type"] == "application/octet-stream"
    assert response.headers["etag"] == f'"{published.files[0].sha256}"'
    assert "no-transform" in response.headers["cache-control"]
    assert MODEL_NAME in response.headers["content-disposition"]


async def test_a_resume_from_the_middle_answers_206_at_exactly_that_offset(
    client: httpx.AsyncClient, published: AsrArtifact
):
    start = 1_000
    async with client as c:
        response = await c.get(_model_url(), headers={"Range": f"bytes={start}-"})
    size = len(MODEL_BYTES)
    assert response.status_code == 206
    assert response.headers["content-range"] == f"bytes {start}-{size - 1}/{size}"
    assert response.headers["content-length"] == str(size - start)
    assert response.content == MODEL_BYTES[start:]
    assert MODEL_BYTES[:start] + response.content == MODEL_BYTES


async def test_a_closed_range_and_a_suffix_range_both_land_on_the_right_bytes(
    client: httpx.AsyncClient, published: AsrArtifact
):
    size = len(MODEL_BYTES)
    async with client as c:
        closed = await c.get(_model_url(), headers={"Range": "bytes=10-19"})
        suffix = await c.get(_model_url(), headers={"Range": "bytes=-16"})
        over = await c.get(_model_url(), headers={"Range": f"bytes=0-{size + 5000}"})
    assert closed.status_code == 206
    assert closed.headers["content-range"] == f"bytes 10-19/{size}"
    assert closed.content == MODEL_BYTES[10:20]

    assert suffix.status_code == 206
    assert suffix.headers["content-range"] == f"bytes {size - 16}-{size - 1}/{size}"
    assert suffix.content == MODEL_BYTES[-16:]

    assert over.status_code == 206
    assert over.headers["content-range"] == f"bytes 0-{size - 1}/{size}"
    assert over.content == MODEL_BYTES


async def test_a_range_that_starts_past_the_end_is_416_and_names_the_real_size(
    client: httpx.AsyncClient, published: AsrArtifact
):
    size = len(MODEL_BYTES)
    async with client as c:
        at_eof = await c.get(_model_url(), headers={"Range": f"bytes={size}-"})
        past_eof = await c.get(_model_url(), headers={"Range": f"bytes={size + 4096}-"})
    for response in (at_eof, past_eof):
        assert response.status_code == 416
        assert response.headers["content-range"] == f"bytes */{size}"
        assert MODEL_BYTES not in response.content


@pytest.mark.parametrize("header", ["bytes=abc", "bytes=-", "items=0-1", "0-1"])
async def test_a_range_header_that_cannot_be_parsed_is_a_400_and_not_a_silent_whole_file(
    client: httpx.AsyncClient, published: AsrArtifact, header: str
):
    async with client as c:
        response = await c.get(_model_url(), headers={"Range": header})
    assert response.status_code == 400
    assert MODEL_BYTES not in response.content


async def test_two_ranges_in_one_request_come_back_as_multipart(
    client: httpx.AsyncClient, published: AsrArtifact
):
    async with client as c:
        response = await c.get(_model_url(), headers={"Range": "bytes=0-9,100-109"})
    assert response.status_code == 206
    assert response.headers["content-type"].startswith("multipart/byteranges; boundary=")
    assert MODEL_BYTES[0:10] in response.content
    assert MODEL_BYTES[100:110] in response.content
    assert b"Content-Range: bytes 0-9/" in response.content
    assert len(response.content) > 20


async def test_a_head_reports_the_length_and_the_digest_without_sending_the_file(
    client: httpx.AsyncClient, published: AsrArtifact
):
    async with client as c:
        response = await c.head(_model_url())
    assert response.status_code == 200
    assert response.content == b""
    assert response.headers["content-length"] == str(len(MODEL_BYTES))
    assert response.headers["accept-ranges"] == "bytes"
    assert response.headers["etag"] == f'"{published.files[0].sha256}"'


async def test_a_head_answers_the_same_way_about_a_range_as_a_get_would(
    client: httpx.AsyncClient, published: AsrArtifact
):
    size = len(MODEL_BYTES)
    async with client as c:
        head = await c.head(_model_url(), headers={"Range": "bytes=64-127"})
        get = await c.get(_model_url(), headers={"Range": "bytes=64-127"})
    assert head.status_code == get.status_code == 206
    assert head.headers["content-range"] == get.headers["content-range"] == f"bytes 64-127/{size}"
    assert head.content == b""
    assert get.content == MODEL_BYTES[64:128]


async def test_a_truncated_file_is_refused_rather_than_served_short(
    client: httpx.AsyncClient, published: AsrArtifact
):
    directory = asr_artifacts.artifact_dir(published)
    (directory / MODEL_NAME).write_bytes(MODEL_BYTES[:900])
    asr_artifacts.clear_digest_cache()

    async with client as c:
        manifest = await c.get("/api/asr-models")
        download = await c.get(_model_url())
        ranged = await c.get(_model_url(), headers={"Range": "bytes=0-99"})

    assert _artifact_of(manifest.json())["unavailableReason"] == ArtifactRefusal.WRONG_SIZE.value
    for response in (download, ranged):
        assert response.status_code == 503
        assert MODEL_BYTES[:100] not in response.content
    detail = download.json()["detail"]
    assert detail == "The offline speech model can't be downloaded right now. Try again later."


async def test_a_directory_standing_in_for_a_file_is_refused(
    client: httpx.AsyncClient, catalogue: AsrArtifact
):
    directory = asr_artifacts.artifact_dir(catalogue)
    (directory / MODEL_NAME).mkdir(parents=True)
    async with client as c:
        response = await c.get(_model_url())
    assert response.status_code == 503
    assert response.json()["detail"]


async def test_two_concurrent_downloads_each_get_their_own_bytes(
    client: httpx.AsyncClient, published: AsrArtifact
):
    size = len(MODEL_BYTES)
    async with client as c:
        first, second, whole = await asyncio.gather(
            c.get(_model_url(), headers={"Range": "bytes=0-511"}),
            c.get(_model_url(), headers={"Range": "bytes=512-"}),
            c.get(_model_url()),
        )
    assert first.status_code == second.status_code == 206
    assert first.content == MODEL_BYTES[:512]
    assert second.content == MODEL_BYTES[512:]
    assert second.headers["content-range"] == f"bytes 512-{size - 1}/{size}"
    assert first.content + second.content == MODEL_BYTES
    assert whole.status_code == 200
    assert whole.content == MODEL_BYTES


async def test_the_second_file_of_the_artifact_is_served_too(
    client: httpx.AsyncClient, published: AsrArtifact
):
    async with client as c:
        response = await c.get(f"/api/asr-models/{ARTIFACT_ID}/files/{TOKENS_NAME}")
    assert response.status_code == 200
    assert response.content == TOKENS_BYTES
    assert response.headers["content-type"] == "application/octet-stream"


@pytest.mark.parametrize(
    "name", ["missing.onnx", "..%2F..%2Fetc%2Fpasswd", "model.int8.onnx.bak", "MODEL.INT8.ONNX"]
)
async def test_a_file_name_the_artifact_does_not_have_is_a_404_and_never_a_path(
    client: httpx.AsyncClient, published: AsrArtifact, name: str
):
    async with client as c:
        response = await c.get(f"/api/asr-models/{ARTIFACT_ID}/files/{name}")
    assert response.status_code == 404
    assert MODEL_BYTES not in response.content


_MAY_DOWNLOAD = sorted(deps.DESIGN_WORKSHOP_ROLES)
_MAY_NOT_DOWNLOAD = sorted(set(deps.ROLE_RANK) - set(deps.DESIGN_WORKSHOP_ROLES))


@pytest.mark.parametrize("role", _MAY_DOWNLOAD)
async def test_the_accounts_that_run_design_workshops_may_download_the_model(
    client: httpx.AsyncClient, published: AsrArtifact, caller: dict[str, Any], role: str
):
    caller["user"] = _person(role)
    async with client as c:
        manifest = await c.get("/api/asr-models")
        download = await c.get(_model_url())
    assert manifest.status_code == 200
    assert download.status_code == 200
    assert download.content == MODEL_BYTES


@pytest.mark.parametrize("role", _MAY_NOT_DOWNLOAD)
async def test_an_account_that_cannot_run_a_workshop_is_refused_the_model(
    client: httpx.AsyncClient, published: AsrArtifact, caller: dict[str, Any], role: str
):
    caller["user"] = _person(role)
    async with client as c:
        manifest = await c.get("/api/asr-models")
        download = await c.get(_model_url())
        head = await c.head(_model_url())
    assert manifest.status_code == 403
    assert download.status_code == 403
    assert head.status_code == 403
    assert MODEL_BYTES not in download.content


async def test_an_unauthenticated_request_gets_401_and_no_bytes(published: AsrArtifact):
    app = FastAPI()
    app.include_router(asr_models.router, prefix="/api")
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url="http://asr.test"
    ) as c:
        manifest = await c.get("/api/asr-models")
        download = await c.get(_model_url())
        ranged = await c.get(_model_url(), headers={"Range": "bytes=0-9"})
    assert manifest.status_code == 401
    assert download.status_code == 401
    assert ranged.status_code == 401
    assert MODEL_BYTES[:10] not in ranged.content



async def test_the_file_is_hashed_once_and_then_remembered(
    client: httpx.AsyncClient, published: AsrArtifact, monkeypatch: pytest.MonkeyPatch
):
    calls: list[Path] = []
    real = asr_artifacts._sha256_of_file

    def counted(path: Path) -> tuple[str, int]:
        calls.append(path)
        return real(path)

    monkeypatch.setattr(asr_artifacts, "_sha256_of_file", counted)
    asr_artifacts.clear_digest_cache()
    async with client as c:
        await c.get(_model_url())
        await c.get(_model_url(), headers={"Range": "bytes=100-199"})
        await c.head(_model_url())
    assert len(calls) == 1, f"the file was hashed {len(calls)} times"


async def test_replacing_the_file_with_different_bytes_is_noticed_without_being_told(
    client: httpx.AsyncClient, published: AsrArtifact
):
    async with client as c:
        first = await c.get(_model_url())
        assert first.status_code == 200
        (asr_artifacts.artifact_dir(published) / MODEL_NAME).write_bytes(MODEL_BYTES + b"tail")
        second = await c.get(_model_url())
        manifest = await c.get("/api/asr-models")
    assert second.status_code == 503
    assert _artifact_of(manifest.json())["unavailableReason"] == ArtifactRefusal.WRONG_SIZE.value


async def test_publishing_by_rename_is_noticed_even_when_the_timestamps_are_preserved(
    client: httpx.AsyncClient, published: AsrArtifact
):
    directory = asr_artifacts.artifact_dir(published)
    target = directory / MODEL_NAME
    before = target.stat()

    async with client as c:
        assert (await c.get(_model_url())).status_code == 200

        substituted = bytes(len(MODEL_BYTES))
        assert len(substituted) == len(MODEL_BYTES) and substituted != MODEL_BYTES
        staged = directory / f".{MODEL_NAME}.staged"
        staged.write_bytes(substituted)
        os.utime(staged, ns=(before.st_atime_ns, before.st_mtime_ns))
        os.replace(staged, target)
        assert target.stat().st_mtime_ns == before.st_mtime_ns, "mtime was not preserved"
        assert target.stat().st_size == before.st_size, "size was not preserved"

        download = await c.get(_model_url())
        manifest = await c.get("/api/asr-models")

    assert download.status_code == 503, "a renamed-in substitution was served from the memo"
    entry = _file_of(_artifact_of(manifest.json()), MODEL_NAME)
    assert entry["available"] is False
    assert entry["unavailableReason"] == ArtifactRefusal.WRONG_DIGEST.value
    assert entry["sha256"] is None


@pytest.mark.skipif(
    os.name == "nt",
    reason=(
        "st_ctime on Windows is the CREATION time, so this is the one case the memo key cannot see: "
        "measured 2026-08-13 on CPython 3.14.6, an in-place same-size write plus os.utime restoring "
        "mtime reproduces the key exactly, and the endpoint published sha256 a7a044…d0b31 while "
        "serving bytes hashing to 407320d1…88d6. Publish by rename — the test above — is what closes "
        "it on this platform."
    ),
)
async def test_an_in_place_overwrite_that_restores_the_timestamps_is_still_noticed(
    client: httpx.AsyncClient, published: AsrArtifact
):
    directory = asr_artifacts.artifact_dir(published)
    target = directory / MODEL_NAME
    before = target.stat()

    async with client as c:
        assert (await c.get(_model_url())).status_code == 200
        with target.open("r+b") as handle:
            handle.seek(0)
            handle.write(b"\x00" * 64)
        os.utime(target, ns=(before.st_atime_ns, before.st_mtime_ns))
        after = target.stat()
        assert (after.st_size, after.st_mtime_ns) == (before.st_size, before.st_mtime_ns)
        download = await c.get(_model_url())

    assert download.status_code == 503
    assert after.st_ctime_ns != before.st_ctime_ns, "st_ctime did not move on a POSIX platform"


def test_the_store_directory_is_read_from_asr_model_dir_and_blank_means_unset(
    monkeypatch: pytest.MonkeyPatch,
):
    from app.core.config import Settings

    field = Settings.model_fields["asr_model_dir"]
    assert field.alias == "ASR_MODEL_DIR"
    assert field.default is None, "a default directory would publish whatever is next to the API"

    monkeypatch.setattr(
        asr_artifacts, "get_settings", lambda: SimpleNamespace(asr_model_dir="  /srv/asr  ")
    )
    assert asr_artifacts.store_root() == Path("/srv/asr")
    for blank in ("", "   ", None):
        monkeypatch.setattr(
            asr_artifacts, "get_settings", lambda blank=blank: SimpleNamespace(asr_model_dir=blank)
        )
        assert asr_artifacts.store_root() is None



def test_the_model_download_is_not_behind_the_dictation_cap_or_the_consent_gate():
    source = (BACKEND / "app" / "api" / "routes" / "asr_models.py").read_text(encoding="utf-8")
    imports = [line for line in source.splitlines() if line.startswith(("import ", "from "))]
    joined = "\n".join(imports)
    for forbidden in ("dictation_cap", "dictation_consent", "ai_verb_cap", "media_queue"):
        assert forbidden not in joined, f"{forbidden} is imported by the model download route"
