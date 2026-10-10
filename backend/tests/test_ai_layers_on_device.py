"""A layer a model on the handset produced: ``POST /design-workshops/{id}/ai-layers/on-device``.

The Android app runs PROOFREAD and TRANSLATION with a Gemma 4 model it downloaded and verified on the
phone (``DwTier2Engine.kt``), then records the words here. Three properties are pinned:

* the planner fixes the tier at TIER_2, records the passage it was given, and refuses a model or a
  kind nobody pinned for a handset;
* the route keeps the designer set and the workshop check in front of it and is refused by the
  schema before either for a body that is not the device contract;
* it runs no model, reaches no provider and is behind neither money gate.
"""

from __future__ import annotations

import inspect
from datetime import UTC, datetime
from types import SimpleNamespace

import pytest

from app.services.ai_layers import AiTier, LayerKind, LayerSource, UNRECORDED
from app.services.ai_verbs import (
    ON_DEVICE_KINDS,
    ON_DEVICE_MODEL_IDS,
    ON_DEVICE_PROVIDER,
    VerbError,
    on_device,
)

_AT = datetime(2026, 10, 10, 9, 30, tzinfo=UTC)


def _plan(**overrides):
    args = dict(
        workshop_id="wsp_1",
        kind=LayerKind.PROOFREAD,
        text="The dabu paste is pressed onto the cloth.",
        source_text="the dabu paist is presed onto the cloth",
        model_id="gemma-4-E2B-it.litertlm",
        model_version="litertlm-android 0.18.0",
        language="en",
        produced_at=_AT,
        created_by_id="usr_1",
    )
    args.update(overrides)
    return on_device(**args)


def test_the_two_pinned_android_models_are_the_only_ones_accepted():
    assert ON_DEVICE_MODEL_IDS == {"gemma-4-E2B-it.litertlm", "gemma-4-E4B-it.litertlm"}
    with pytest.raises(VerbError):
        _plan(model_id="some-other-model.litertlm")


def test_only_proofread_and_translation_are_accepted_from_a_handset():
    assert ON_DEVICE_KINDS == {LayerKind.PROOFREAD, LayerKind.TRANSLATION}
    with pytest.raises(VerbError):
        _plan(kind=LayerKind.EXPANDED)


def test_the_tier_is_tier_2_and_the_provider_is_the_runtime():
    data = _plan().data
    assert data["tier"] == AiTier.TIER_2.value
    assert data["kind"] == LayerKind.PROOFREAD.value
    assert data["provider"] == ON_DEVICE_PROVIDER
    assert data["modelId"] == "gemma-4-E2B-it.litertlm"
    assert data["modelVersion"] == "litertlm-android 0.18.0"
    assert data["producedAt"] == _AT


def test_the_passage_the_model_was_given_is_recorded_as_the_source():
    data = _plan().data
    assert "the dabu paist is presed onto the cloth" in data.values()
    assert data["text"] == "The dabu paste is pressed onto the cloth."


def test_a_translation_records_its_target_and_an_unrecorded_source_language():
    data = _plan(kind=LayerKind.TRANSLATION, language="Odia", text="ଡାବୁ").data
    assert data["kind"] == LayerKind.TRANSLATION.value
    assert "Odia" in data.values()
    assert UNRECORDED in data.values()


def test_no_acceptance_is_set_by_a_device_layer():
    data = _plan().data
    assert not any(key.startswith("accepted") for key in data)


def test_the_planner_is_the_shared_one_and_not_a_copy():
    """It must go through ``proofread`` / ``translate`` so the layer law is the one cloud rows meet."""
    from app.services import ai_verbs

    source = inspect.getsource(ai_verbs.on_device)
    assert "return translate(" in source and "return proofread(" in source
    assert "LayerSource.supplied_text(" in source
    assert isinstance(LayerSource.supplied_text("x"), LayerSource)


def test_the_route_calls_no_provider_and_neither_money_gate():
    from app.api.routes import design_workshops as routes

    source = inspect.getsource(routes.record_on_device_ai_layer)
    body = source.split('"""', 2)[-1]
    for forbidden in (
        "ai.proofread_text",
        "ai.translate_text",
        "_verb_gate(",
        "ai_verb_cap.",
        "dictation_consent.",
        "dictation_cap.",
        "_finish_verb(",
    ):
        assert forbidden not in body, f"the on-device route reaches {forbidden}"
    assert "_require_designer(current_user)" in body
    assert "load_workshop_or_404(workshop_id, current_user, for_edit=True)" in body


# ── Over HTTP, with the database replaced by a tripwire ────────────────────────────────────────


class _DatabaseTouched(Exception):
    """The route's guards all passed and its body started working."""


class _Tripwire:
    def __getattr__(self, name: str):
        raise _DatabaseTouched(name)


_CALLER: dict[str, object] = {"user": None}

_BODY = {
    "kind": "PROOFREAD",
    "text": "The dabu paste is pressed onto the cloth.",
    "provider": "litert-lm",
    "modelId": "gemma-4-E2B-it.litertlm",
    "modelVersion": "litertlm-android 0.18.0",
    "language": "en",
    "producedAt": "2026-10-10T09:30:00+05:30",
    "sourceText": "the dabu paist is presed onto the cloth",
}
_PATH = "/design-workshops/wsp_1/ai-layers/on-device"


@pytest.fixture
def api(monkeypatch):
    import sys

    import httpx
    from fastapi import FastAPI

    import app.core.db as core_db
    from app.api.routes import design_workshops as routes
    from app.core import deps

    tripwire = _Tripwire()
    real_db = core_db.db
    monkeypatch.setattr(core_db, "db", tripwire)
    for module in list(sys.modules.values()):
        if getattr(module, "__name__", "").startswith("app.") and getattr(module, "db", None) is real_db:
            monkeypatch.setattr(module, "db", tripwire)

    app = FastAPI()
    app.include_router(routes.router, prefix="/api")
    app.dependency_overrides[deps.get_current_user] = lambda: _CALLER["user"]

    def call(role: str, body):
        import asyncio

        _CALLER["user"] = SimpleNamespace(id="usr_1", email="x@example.test", name="T", role=role)

        async def run():
            transport = httpx.ASGITransport(app=app)
            async with httpx.AsyncClient(transport=transport, base_url="http://tier2.test") as c:
                response = await c.post(f"/api{_PATH}", json=body)
            return SimpleNamespace(reached=False, status_code=response.status_code)

        try:
            return asyncio.run(run())
        except _DatabaseTouched:
            return SimpleNamespace(reached=True, status_code=None)

    yield call
    _CALLER["user"] = None


@pytest.mark.parametrize("role", ["RESEARCHER", "PROFESSOR"])
def test_only_the_designer_set_may_record_a_device_layer(api, role):
    outcome = api(role, _BODY)
    assert outcome.reached is False
    assert outcome.status_code == 403


def test_a_designer_meets_the_workshop_check_next(api):
    assert api("DESIGNER", _BODY).reached is True


@pytest.mark.parametrize(
    "change",
    [
        {"tier": "TIER_3"},
        {"accepted": True},
        {"kind": "EXPANDED"},
        {"provider": "openai"},
        {"producedAt": "yesterday"},
        {"text": ""},
        {"sourceText": "   "},
        {"sourceLayerId": "lyr_1"},
    ],
)
def test_a_body_that_is_not_the_device_contract_is_refused_before_any_gate(api, change):
    outcome = api("DESIGNER", {**_BODY, **change})
    assert outcome.reached is False
    assert outcome.status_code == 422


def test_the_android_body_and_this_schema_name_the_same_keys():
    """``dwTier2LayerBody`` in ``DwTier2Layer.kt`` sends these keys for a supplied-text run."""
    from app.schemas.design_workshops import AiOnDeviceLayerIn

    assert set(AiOnDeviceLayerIn.model_fields) == set(_BODY)
    assert AiOnDeviceLayerIn(**_BODY).modelId in ON_DEVICE_MODEL_IDS
