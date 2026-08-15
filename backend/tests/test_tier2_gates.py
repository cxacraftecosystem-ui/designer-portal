"""**THE TWO MONEY GATES, PROVED ABSENT FROM THE TIER 2 PATH BY RUNNING IT — NOT BY READING IT.**

``tests/test_asr_model_download.py`` already asserts that ``app/api/routes/asr_models.py`` does not
IMPORT ``dictation_cap``, ``dictation_consent``, ``ai_verb_cap`` or ``media_queue``. That test is
worth keeping and it is not sufficient, for one specific reason: **an import check cannot see an
indirect call.** A gate reached through a shared dependency, a middleware, a service this route does
import, or a late ``from app.services import dictation_cap`` inside a function body, passes it
untouched. What follows drives the route over ASGI with a **tripwire installed on every public
callable of all three gate modules**, so any call by any path — direct, indirect, or lazily imported
— fails the test by name.

WHY THIS FILE IS ABOUT TIER 2 WHEN THE ROUTE IT CALLS SERVES TIER 1 ARTIFACTS.

There is **no Tier 2 route on this server today**, and that is stated rather than worked around:
``DW_TIER2_ARTIFACTS`` in ``DwTier2Models.kt`` is deliberately built in ``ASR_MODEL_ARTIFACTS``'
shape so that publishing a language model is a catalogue entry on **this** endpoint rather than a
second download path with a second set of gates. So this endpoint is the one a Tier 2 download will
travel over, and the property being pinned is the property it must still have on the day a
``.litertlm`` row appears in the catalogue. The device half is pinned on the Kotlin side
(``DwTier2LayerTest``); this is the server half.

THE ARGUMENT, WHICH IS THE REPOSITORY OWNER'S AND NOT AN INFERENCE. The cap *"should only apply to
the global /dictate when it is utilizing the ElevenLabs / Deepgram / Whisper API, and not … the one
through sherpa-onnx or from the local SLM"*. A model that runs on the handset spends nothing at a
provider and sends no artisan's voice anywhere, so neither gate has anything to weigh. The failure
being prevented is concrete: a designer refused a **download** at 21:00 because they had used up a
**transcription** allowance, or refused it because an artisan in an unrelated workshop said no to
having their voice sent out.
"""

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

# Not a round number of anything: an off-by-one in a range offset has to show up as a wrong byte.
MODEL_BYTES = bytes(range(256)) * 5 + b"\x21\x0c"  # 1,282 bytes
ARTIFACT_ID = "test-tier2-gate-artifact"
MODEL_NAME = "model.int8.onnx"

#: The three modules that must never be consulted on this path, with the reason each is here.
GATE_MODULES = {
    # The Tier 3 consent gate: exists because a recording of an artisan's voice leaves the handset.
    "dictation_consent": dictation_consent,
    # The daily ceiling on provider spend for dictation.
    "dictation_cap": dictation_cap,
    # The same ceiling for the AI verbs.
    "ai_verb_cap": ai_verb_cap,
}


def _sha(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


@pytest.fixture
def tripwires(monkeypatch: pytest.MonkeyPatch) -> list[str]:
    """Replace every public callable in the three gate modules with one that fails the test.

    Functions AND classes, because ``dictation_consent``'s gate is reached through ``Send`` and
    ``DictationConsent`` as much as through ``gate_refusal``, and a constructor call is a
    consultation. Enums are left alone — ``DictationConsent.REFUSED`` is a value, not a decision, and
    replacing it would break the module's own import-time constants rather than catch anything.

    The list this returns is empty when nothing was tripped; a test asserts that, so a future
    refactor that renames every gate function cannot make this file silently vacuous.
    """
    tripped: list[str] = []
    patched = 0
    for module_name, module in GATE_MODULES.items():
        for attr, value in vars(module).copy().items():
            if attr.startswith("_") or not callable(value):
                continue
            if getattr(value, "__module__", None) != module.__name__:
                continue  # imported from elsewhere; not this module's gate
            if isinstance(value, type) and issubclass(value, BaseException):
                continue  # an exception class is raised, never consulted
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
    """One synthetic artifact, on disk, correct. These bytes are not a model."""
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


# =================================================================================================
# The whole file, and a resumed range, with both gates armed to fail on contact
# =================================================================================================


async def test_the_artifact_serves_in_full_with_both_gates_armed_to_fail_on_contact(
    client: httpx.AsyncClient, published: AsrArtifact, tripwires: list[str]
):
    """The bytes arrive, and nothing in either gate module was called to let them.

    Asserted on the BODY and not only on the status: a 200 whose length header is right and whose
    body is short is the one failure this endpoint can produce that a client cannot detect.
    """
    async with client as c:
        response = await c.get(_url())
    assert response.status_code == 200
    assert response.content == MODEL_BYTES, "a short body is the failure a client cannot see"
    assert tripwires == [], f"a gate was consulted: {tripwires}"


async def test_a_resumed_range_is_served_with_both_gates_armed(
    client: httpx.AsyncClient, published: AsrArtifact, tripwires: list[str]
):
    """Resuming is the request a designer on a district-town connection actually makes.

    A gate consulted only on the second request would be worse than one consulted on the first: the
    download would fail halfway, having already spent the bundle.
    """
    async with client as c:
        response = await c.get(_url(), headers={"Range": "bytes=400-"})
    assert response.status_code == 206
    assert response.content == MODEL_BYTES[400:]
    assert tripwires == []


# =================================================================================================
# The two states that would refuse a Tier 3 dictation, and do not refuse this
# =================================================================================================


async def test_a_refused_consent_does_not_stand_in_front_of_an_on_device_model(
    client: httpx.AsyncClient, published: AsrArtifact, tripwires: list[str]
):
    """**CONSENT REFUSED** — the strongest state the gate has — and the artifact still serves.

    ``REFUSED`` is not "not yet asked": an artisan has been asked and said no, and ``gate_refusal``
    turns that into a sentence for every Tier 3 send. It refuses a recording LEAVING the phone. This
    file travels the other way, and it is not that artisan's voice; refusing it here would mean one
    artisan's answer in one workshop stopped a designer installing software.
    """
    assert (
        dictation_consent.DictationConsent.REFUSED.value == "REFUSED"
    ), "the state being asserted about has to be the real one"
    async with client as c:
        response = await c.get(_url())
    assert response.status_code == 200
    assert response.content == MODEL_BYTES
    assert tripwires == []


def test_a_fully_spent_allowance_really_is_refused_for_a_dictation():
    """**THE GATE IS REAL AND ARMED**, established without a tripwire in the way.

    This is half of one argument and it comes first deliberately: the test below asserts that an
    exhausted cap does NOT refuse a model download, and that assertion is worth nothing unless an
    exhausted cap refuses something. So the refusal is demonstrated here against the real
    ``cap_refusal``, and the route is exercised there against a tripwired one.
    """
    spent = dictation_cap.Allowance(day=dictation_cap.ist_day(), limit=25, used=25)
    assert spent.remaining == 0 and spent.spent
    refusal = dictation_cap.cap_refusal(spent)
    assert refusal, "a spent allowance must refuse a DICTATION, or the test below proves nothing"
    assert refusal.strip() == refusal, "a refusal is a sentence, not a padded string"


async def test_an_exhausted_daily_cap_does_not_stand_in_front_of_an_on_device_model(
    client: httpx.AsyncClient, published: AsrArtifact, tripwires: list[str]
):
    """**THE CAP FULLY SPENT**, and the artifact still serves.

    The exhausted state is not set up here, because the point is that there is nowhere to set it up
    THAT THIS ROUTE WOULD READ: no allowance is loaded, no day is computed, no usage row is counted.
    The tripwires are what prove that — ``dictation_cap.load_allowance``, ``.spend``, ``.ist_day``
    and ``.cap_refusal`` are all armed, and a route that consulted the cap at all would have to call
    one of them.
    """
    async with client as c:
        response = await c.get(_url())
    assert response.status_code == 200
    assert response.content == MODEL_BYTES
    assert tripwires == []


# =================================================================================================
# The tripwire itself
# =================================================================================================


def test_the_tripwire_would_actually_fire(tripwires: list[str]):
    """**THE TEST OF THE TEST.** A tripwire nobody has seen fire is an assumption.

    Every assertion above is of the form "nothing was tripped". That is exactly the shape that
    passes when the mechanism is broken, so the mechanism is exercised here: call a patched gate
    function and require it to blow up and to name itself.
    """
    with pytest.raises(AssertionError) as caught:
        dictation_cap.cap_refusal(None)
    assert "dictation_cap.cap_refusal" in str(caught.value)
    assert tripwires == ["dictation_cap.cap_refusal"]

    with pytest.raises(AssertionError):
        dictation_consent.gate_refusal(None)
    assert "dictation_consent.gate_refusal" in tripwires


def test_the_route_module_reaches_no_gate_even_through_a_lazy_import():
    """The import check's blind spot, closed: a ``from app.services import …`` inside a function.

    ``test_asr_model_download.py`` reads only lines that START with ``import``/``from``, so an
    indented import inside a handler is invisible to it. This reads every line.
    """
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
    """The route's declared dependencies, walked. A gate in a ``Depends`` is the invisible way in.

    The tripwire fixture catches a call at run time; this catches a gate wired in as a dependency
    that happens not to be exercised by the two requests above.
    """
    route = next(
        r
        for r in asr_models.router.routes
        if getattr(r, "name", None) == asr_models._FILE_ROUTE
        and "GET" in getattr(r, "methods", set())
    )
    seen: set[Any] = set()
    # FastAPI calls it ``.call``; a sub-dependency is itself a ``Dependant`` with its own list.
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
