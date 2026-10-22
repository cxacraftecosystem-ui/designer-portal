"""Designer-portal's wiring of the shared questionnaire (UNIFY_2026-10-21.md, stream U1): no database.

* every port the package asks for is served (the consent gate, the transcription enqueue and the
  workshop-submission rules are this portal's; U0 shipped them refusing);
* the v2 routes are mounted, and answer 404 while ``QUESTIONNAIRE_V2`` is off (except the config);
* the consent gate is the shared ledger rule PLUS the design workshop's dictation consent (C4, DR
  audit H3): a refused workshop refuses a take's send even when every artisan agreed;
* the readers (tasks, completeness, stage counts, export, annexure) add nothing, and read nothing,
  while the switch is off; with it on, a take or a typed answer counts for a task (C10);
* the old-build shims are this portal's module and run nothing while the switch is off.
"""

from __future__ import annotations

import asyncio
import re
from datetime import UTC, datetime
from types import SimpleNamespace
from typing import Any

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

DESIGNER = SimpleNamespace(id="u_designer", role="DESIGNER", name="Nandini")


def _routes() -> set[tuple[str, str]]:
    """Every (method, path) the API serves, through included routers (FastAPI no longer flattens)."""
    from app.api.router import api_router

    try:
        from fastapi.routing import iter_route_contexts

        routes = list(iter_route_contexts(api_router.routes))
    except ImportError:  # an older FastAPI keeps them on .routes
        routes = list(api_router.routes)
    have = set()
    for route in routes:
        path = re.sub(r"\{[^}]+\}", "{}", getattr(route, "path", ""))
        for method in getattr(route, "methods", None) or ():
            have.add((method, path))
    return have


def test_every_port_is_served_on_designer_portal() -> None:
    from app.questionnaire import ports

    impl = ports.impl()
    assert impl.PORTAL == "designer-portal"
    for name in ports.MODULES:
        module = impl.module(name)  # raises PortUnavailable if a port were still refused
        assert module is not None, name
    assert impl.module("consent_gate").__name__ == "app.services.questionnaire_consent"
    assert impl.module("transcription").__name__ == "app.services.questionnaire_transcription"
    assert impl.module("workshop_access").__name__ == "app.services.workshop_access"
    gate = impl.module("consent_gate")
    for name in ("ALLOW", "TRANSCRIPT_STATUS_FOR", "verdict", "verdict_details", "verdict_detail", "current_states"):
        assert hasattr(gate, name), name
    enqueue = impl.module("transcription")
    assert hasattr(enqueue, "enqueue") and hasattr(enqueue, "is_paused")


@pytest.mark.parametrize("method,path", [
    ("GET", "/api/questionnaire/config"),
    ("POST", "/api/questionnaire/sessions"),
    ("GET", "/api/questionnaire/sessions"),
    ("GET", "/api/questionnaire/sessions/{}"),
    ("PATCH", "/api/questionnaire/sessions/{}"),
    ("DELETE", "/api/questionnaire/sessions/{}"),
    ("POST", "/api/questionnaire/sessions/{}/restore"),
    ("POST", "/api/questionnaire/sessions/{}/respondents"),
    ("PATCH", "/api/questionnaire/sessions/{}/respondents/{}"),
    ("POST", "/api/questionnaire/respondents/{}/register"),
    ("POST", "/api/questionnaire/sessions/join"),
    ("POST", "/api/questionnaire/sessions/continue"),
    ("POST", "/api/questionnaire/sessions/{}/merge-into/{}"),
    ("GET", "/api/questionnaire/form-for"),
    ("GET", "/api/questionnaire/forms/{}/bindings"),
    ("GET", "/api/questionnaire/forms/{}/structure"),
    ("PUT", "/api/questionnaire/forms/{}/bindings"),
    ("PUT", "/api/questionnaire/type-defaults"),
    ("POST", "/api/questionnaire/coverage/check"),
    ("GET", "/api/questionnaire/coverage"),
    ("PUT", "/api/questionnaire/answers"),
    ("GET", "/api/questionnaire/answers/{}/revisions"),
    ("GET", "/api/questionnaire/recordings/removed"),
    ("GET", "/api/questionnaire/recordings/{}/events"),
    ("GET", "/api/questionnaire/inbox"),
    ("GET", "/api/questionnaire/annexure"),
    ("GET", "/api/questionnaire/corpus.csv"),
    ("GET", "/api/artisans/{}/record"),
    ("GET", "/api/artisans/{}/record.csv"),
    ("GET", "/api/media/uploads/{}"),
    ("POST", "/api/media/presign"),
    ("POST", "/api/media/multipart/create"),
    ("POST", "/api/media/complete"),
    ("GET", "/api/admin/app-versions"),
])
def test_the_shared_routes_are_mounted(method: str, path: str) -> None:
    assert (method, path) in _routes()


def _client(monkeypatch: pytest.MonkeyPatch, user: Any = DESIGNER) -> TestClient:
    from app.api.routes import questionnaire_v2
    from app.core.deps import get_current_user
    from app.services import questionnaire_transcription

    async def paused(_client: Any = None) -> bool:
        return True

    monkeypatch.setattr(questionnaire_transcription, "is_paused", paused)
    app = FastAPI()
    app.include_router(questionnaire_v2.router, prefix="/api")
    app.dependency_overrides[get_current_user] = lambda: user
    return TestClient(app)


def test_v2_is_dark_when_switched_off_and_the_config_says_so(monkeypatch: pytest.MonkeyPatch) -> None:
    from app.core.config import Settings, get_settings

    assert Settings.model_fields["questionnaire_v2"].default is True, "the switch-on commit defaults it on"
    monkeypatch.setattr(get_settings(), "questionnaire_v2", False)
    client = _client(monkeypatch)
    config = client.get("/api/questionnaire/config").json()
    assert config["v2Enabled"] is False
    assert config["portal"] == "designer-portal"
    assert set(config["minVersionCode"]) == {"android", "web"}
    assert config["transcriptionPaused"] is True
    refused = client.post("/api/questionnaire/sessions", json={})
    assert refused.status_code == 404
    assert refused.json()["detail"]["code"] == "NOT_FOUND"


# ── the consent gate: the ledger rule plus the design workshop's dictation consent (C4) ─────────
class GateDB:
    """Answers the gate's reads from a small in-memory picture of one take."""

    def __init__(self, *, artisan_state: str | None = None, workshop_state: str | None = None,
                 recording_state: str | None = None) -> None:
        self.artisan_state, self.workshop_state, self.recording_state = artisan_state, workshop_state, recording_state

    def _row(self, subject: str, state: str | None) -> list[dict[str, Any]]:
        if state is None:
            return []
        return [{"subjectId": subject, "state": state, "recordedAt": datetime(2026, 10, 20, tzinfo=UTC), "source": "researcher"}]

    async def query_raw(self, sql: str, *args: Any) -> list[dict[str, Any]]:
        if "consent_current_v" in sql:
            kind = args[0]
            if kind == "RECORDING":
                return self._row("m1", self.recording_state)
            if kind == "ARTISAN":
                return self._row("a1", self.artisan_state) if "a1" in args[2] else []
            if kind == "WORKSHOP":
                return self._row("dw1", self.workshop_state) if "dw1" in args[2] else []
            return []
        if 'FROM "QuestionnaireRecording" r' in sql:
            return [{"m": "m1", "artisanId": "a1"}]
        if 'JOIN "QuestionnaireInterview" i' in sql:
            return [{"m": "m1", "w": "dw1"}]
        return []

    @property
    def designworkshop(self) -> Any:
        async def find_unique(where: dict[str, Any]) -> Any:
            return SimpleNamespace(id=where["id"], dictationConsent="NOT_RECORDED", dictationConsentAt=None, dictationConsentById=None)

        return SimpleNamespace(find_unique=find_unique)


@pytest.mark.parametrize("artisan,workshop,recording,expected", [
    (None, None, None, "ALLOW"),          # opt-out: no answer anywhere is agreement
    ("GRANTED", None, None, "ALLOW"),
    ("REFUSED", None, None, "REFUSED"),   # an artisan's refusal
    ("GRANTED", "REFUSED", None, "REFUSED"),  # the design workshop's dictation refusal (DR audit H3)
    (None, "GRANTED", "REFUSED", "REFUSED"),  # the recording's own refusal always refuses
])
def test_the_gate_reads_the_subjects_and_the_design_workshop(artisan, workshop, recording, expected) -> None:
    from app.services import questionnaire_consent

    db = GateDB(artisan_state=artisan, workshop_state=workshop, recording_state=recording)
    details = asyncio.run(questionnaire_consent.verdict_details(["m1"], client=db))
    assert details["m1"]["verdict"] == expected
    assert asyncio.run(questionnaire_consent.verdict("m1", client=db)) == expected


def test_recording_never_asks_the_gate() -> None:
    """Consent gates SENDS only: the take routes (presign, complete, file, answers) never import it."""
    from pathlib import Path

    for name in ("api/routes/media.py", "questionnaire/sessions.py", "questionnaire/answer_lock.py"):
        text = (Path(__file__).resolve().parents[1] / "app" / name).read_text(encoding="utf-8")
        assert "questionnaire_consent" not in text, name


# ── readers: nothing while off; a take or a typed answer counts for a task while on (C10) ───────
class Tripwire:
    def __getattr__(self, name: str) -> Any:
        raise AssertionError(f"a reader touched the database while QUESTIONNAIRE_V2 is off ({name})")


def test_the_readers_read_nothing_while_v2_is_off(monkeypatch: pytest.MonkeyPatch) -> None:
    from app.core.config import get_settings
    from app.services import questionnaire_readers as r

    monkeypatch.setattr(get_settings(), "questionnaire_v2", False)

    trip = Tripwire()
    assert asyncio.run(r.forms_for_design_workshop("dw1", client=trip)) == []
    assert asyncio.run(r.session_counts(["f1"], client=trip)) == {}
    assert asyncio.run(r.answer_counts(["s1"], client=trip)) == {}
    assert asyncio.run(r.covered_pairs("u1", ["sec1"], client=trip)) == set()
    assert asyncio.run(r.session_answers(["s1"], client=trip)) == {}
    assert asyncio.run(r.annexure_sittings(["f1"], design_workshop_id="dw1", client=trip)) == {}


def test_a_typed_answer_or_a_take_counts_towards_a_section_task(monkeypatch: pytest.MonkeyPatch) -> None:
    from app.api.routes import tasks
    from app.services import questionnaire_readers

    async def no_responses(**_kwargs: Any) -> list[Any]:
        return []

    async def covered(assignee_id: str, section_ids: list[str], *, workshop_id: str | None = None) -> set[tuple[str, str]]:
        assert assignee_id == "u1"
        return {("A:a1", "sec1"), ("R:guest", "sec2"), ("A:a2", "sec2")}

    monkeypatch.setattr(tasks.db, "questionnaireresponse", SimpleNamespace(find_many=no_responses), raising=False)
    monkeypatch.setattr(questionnaire_readers, "covered_pairs", covered)
    assert asyncio.run(tasks._count_sections("u1", None, [], ["sec1", "sec2"])) == 2
    assert asyncio.run(tasks._count_sections("u1", None, ["a1"], ["sec1", "sec2"])) == 1, "only the named artisans' pairs"


# ── the old-build shims are this portal's and dark while off ───────────────────────────────────
def test_the_shims_run_nothing_while_v2_is_off(monkeypatch: pytest.MonkeyPatch) -> None:
    from app.core.config import get_settings
    from app.questionnaire import shims_dr

    monkeypatch.setattr(get_settings(), "questionnaire_v2", False)

    assert shims_dr.enabled() is False
    assert asyncio.run(shims_dr.form_is_unified("f1", client=Tripwire())) is False
    payload = SimpleNamespace(designWorkshopId="dw1", artisanIds=["a1"], title="Old title")
    assert asyncio.run(shims_dr.create_global_interview(payload, DESIGNER)) is None


def test_the_build_headers_are_read_by_number_not_name() -> None:
    from app.questionnaire import shims_dr

    assert shims_dr.client_build({"x-app-platform": "android", "x-app-version-code": "28", "x-app-version": "0.0.2"}) == ("android", 28, "0.0.2")
    assert shims_dr.client_build({"x-app-version": "web-0.0.2"})[0] == "web"


def test_the_app_version_middleware_is_installed() -> None:
    from app.main import app
    from app.services.app_versions import AppVersionMiddleware

    assert any(getattr(m, "cls", None) is AppVersionMiddleware for m in app.user_middleware)
