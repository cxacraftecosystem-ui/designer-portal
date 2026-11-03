import enum
import hashlib
import inspect
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import httpx
import pytest
from fastapi import FastAPI

from app.api.routes import asr_models
from app.core import deps
from app.services import ai_verb_cap, asr_artifacts, dictation_cap, dictation_consent
from app.services.asr_artifacts import AsrArtifact, AsrArtifactFile

MODEL_BYTES = bytes(range(256)) * 5 + b"\x21\x0c"
ARTIFACT_ID = "test-tier2-gate-artifact"
MODEL_NAME = "model.int8.onnx"

GATE_MODULES = {
    "dictation_consent": dictation_consent,
    "dictation_cap": dictation_cap,
    "ai_verb_cap": ai_verb_cap,
}


def _sha(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


@pytest.fixture
def tripwires(monkeypatch: pytest.MonkeyPatch) -> list[str]:
    tripped: list[str] = []
    patched = 0
    for module_name, module in GATE_MODULES.items():
        for attr, value in vars(module).copy().items():
            if attr.startswith("_") or not callable(value):
                continue
            if getattr(value, "__module__", None) != module.__name__:
                continue
            if isinstance(value, type) and issubclass(value, BaseException):
                continue
            if isinstance(value, type) and issubclass(value, enum.Enum):
                continue

            def _tripwire(*_a: Any, _n: str = f"{module_name}.{attr}", **_k: Any) -> Any:
                tripped.append(_n)
                raise AssertionError(
                    f"{_n} was consulted while serving an on-device model artifact. A model that "
                    "runs on the handset spends nothing at a provider and sends no recording off "
                    "the phone, so neither the daily cap nor the Tier 3 consent gate has anything "
                    "to weigh here."
                )

            monkeypatch.setattr(module, attr, _tripwire, raising=False)
            patched += 1
    assert patched >= 12, (
        f"only {patched} gate callables were tripwired — the modules were probably renamed, and a "
        "tripwire that patches nothing proves nothing"
    )
    return tripped


@pytest.fixture
def published(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> AsrArtifact:
    artifact = AsrArtifact(
        artifact_id=ARTIFACT_ID,
        version="2026-01-01",
        quantisation="int8",
        languages=("hi-IN",),
        language_note="Synthetic row; measured on nothing and never published.",
        upstream_version="tests/test_tier2_gates.py",
        provenance="Written by this test module.",
        files=(
            AsrArtifactFile(
                file_name=MODEL_NAME, sha256=_sha(MODEL_BYTES), bytes=len(MODEL_BYTES)
            ),
        ),
    )
    root = tmp_path / "asr-models"
    root.mkdir()
    monkeypatch.setattr(asr_artifacts, "store_root", lambda: root)
    monkeypatch.setattr(asr_artifacts, "ASR_MODEL_ARTIFACTS", (artifact,))
    asr_artifacts.clear_digest_cache()
    directory = asr_artifacts.artifact_dir(artifact)
    assert directory is not None
    directory.mkdir(parents=True, exist_ok=True)
    (directory / MODEL_NAME).write_bytes(MODEL_BYTES)
    yield artifact
    asr_artifacts.clear_digest_cache()


@pytest.fixture
def client() -> httpx.AsyncClient:
    app = FastAPI()
    app.include_router(asr_models.router, prefix="/api")
    app.dependency_overrides[deps.get_current_user] = lambda: SimpleNamespace(
        id="user-designer", email="designer@example.test", role="DESIGNER"
    )
    return httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url="http://tier2.test"
    )


def _url() -> str:
    return f"/api/asr-models/{ARTIFACT_ID}/files/{MODEL_NAME}"



async def test_the_artifact_serves_in_full_with_both_gates_armed_to_fail_on_contact(
    client: httpx.AsyncClient, published: AsrArtifact, tripwires: list[str]
):
    async with client as c:
        response = await c.get(_url())
    assert response.status_code == 200
    assert response.content == MODEL_BYTES, "a short body is the failure a client cannot see"
    assert tripwires == [], f"a gate was consulted: {tripwires}"


async def test_a_resumed_range_is_served_with_both_gates_armed(
    client: httpx.AsyncClient, published: AsrArtifact, tripwires: list[str]
):
    async with client as c:
        response = await c.get(_url(), headers={"Range": "bytes=400-"})
    assert response.status_code == 206
    assert response.content == MODEL_BYTES[400:]
    assert tripwires == []



async def test_a_refused_consent_does_not_stand_in_front_of_an_on_device_model(
    client: httpx.AsyncClient, published: AsrArtifact, tripwires: list[str]
):
    assert (
        dictation_consent.DictationConsent.REFUSED.value == "REFUSED"
    ), "the state being asserted about has to be the real one"
    async with client as c:
        response = await c.get(_url())
    assert response.status_code == 200
    assert response.content == MODEL_BYTES
    assert tripwires == []


def test_a_fully_spent_allowance_really_is_refused_for_a_dictation():
    spent = dictation_cap.Allowance(day=dictation_cap.ist_day(), limit=25, used=25)
    assert spent.remaining == 0 and spent.spent
    refusal = dictation_cap.cap_refusal(spent)
    assert refusal, "a spent allowance must refuse a DICTATION, or the test below proves nothing"
    assert refusal.strip() == refusal, "a refusal is a sentence, not a padded string"


async def test_an_exhausted_daily_cap_does_not_stand_in_front_of_an_on_device_model(
    client: httpx.AsyncClient, published: AsrArtifact, tripwires: list[str]
):
    async with client as c:
        response = await c.get(_url())
    assert response.status_code == 200
    assert response.content == MODEL_BYTES
    assert tripwires == []



def test_the_tripwire_would_actually_fire(tripwires: list[str]):
    with pytest.raises(AssertionError) as caught:
        dictation_cap.cap_refusal(None)
    assert "dictation_cap.cap_refusal" in str(caught.value)
    assert tripwires == ["dictation_cap.cap_refusal"]

    with pytest.raises(AssertionError):
        dictation_consent.gate_refusal(None)
    assert "dictation_consent.gate_refusal" in tripwires


def test_the_route_module_reaches_no_gate_even_through_a_lazy_import():
    source = (
        Path(asr_models.__file__).read_text(encoding="utf-8")
    )
    for line in source.splitlines():
        stripped = line.strip()
        if not stripped.startswith(("import ", "from ")):
            continue
        for forbidden in ("dictation_cap", "dictation_consent", "ai_verb_cap", "media_queue"):
            assert forbidden not in stripped, (
                f"{forbidden} is imported by the model download route at any indentation: {line!r}"
            )


def test_no_dependency_of_the_file_route_can_reach_a_gate():
    route = next(
        r
        for r in asr_models.router.routes
        if getattr(r, "name", None) == asr_models._FILE_ROUTE
        and "GET" in getattr(r, "methods", set())
    )
    seen: set[Any] = set()
    pending = [route.endpoint]
    queue = list(route.dependant.dependencies)
    while queue:
        dependant = queue.pop()
        if dependant.call is not None:
            pending.append(dependant.call)
        queue.extend(dependant.dependencies)
    assert len(pending) >= 2, (
        "the file route declares no dependency at all — it has at least an identity one, so this "
        "walk is looking at the wrong route"
    )
    while pending:
        fn = pending.pop()
        if fn in seen:
            continue
        seen.add(fn)
        module = getattr(fn, "__module__", "") or ""
        assert not any(
            gate in module for gate in GATE_MODULES
        ), f"{getattr(fn, '__qualname__', fn)} comes from a gate module ({module})"
        try:
            source = inspect.getsource(fn)
        except (OSError, TypeError):
            continue
        for gate in GATE_MODULES:
            assert gate not in source, f"{getattr(fn, '__qualname__', fn)} names {gate}"
