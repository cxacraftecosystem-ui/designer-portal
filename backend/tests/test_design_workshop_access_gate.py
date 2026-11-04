import asyncio
import sys
from types import SimpleNamespace
from typing import Any

import httpx
import pytest
from fastapi import FastAPI

import app.core.db as core_db
from app.api.router import api_router
from app.core import deps
from app.services.design_workshop_access import code_check

WORKSHOP_ID = "cmgatetest0000000000000aa"


class _DatabaseTouched(Exception):
    pass


class _Tripwire:
    def __init__(self) -> None:
        object.__setattr__(self, "touched", False)

    def __getattr__(self, name: str) -> Any:
        object.__setattr__(self, "touched", True)
        raise _DatabaseTouched(name)


class _Outcome:
    def __init__(self, *, reached: bool, status_code: int | None = None, detail: Any = "") -> None:
        self.reached = reached
        self.status_code = status_code
        self.detail = str(detail)

    def __repr__(self) -> str:  # pragma: no cover - only read out of a failure message
        return "reached-the-database" if self.reached else f"HTTP {self.status_code}: {self.detail}"


_CURRENT: dict[str, Any] = {"user": None}


def _build_app() -> FastAPI:
    application = FastAPI()
    application.include_router(api_router)
    application.dependency_overrides[deps.get_current_user] = lambda: _CURRENT["user"]
    return application


_APP = _build_app()


def _user(role: str) -> SimpleNamespace:
    return SimpleNamespace(
        id=f"u-{role.lower()}", email=f"{role.lower()}@example.test", name="Gate Test", role=role
    )


@pytest.fixture
def api(monkeypatch: pytest.MonkeyPatch):
    tripwire = _Tripwire()
    real_db = core_db.db
    monkeypatch.setattr(core_db, "db", tripwire)
    for module in list(sys.modules.values()):
        name = getattr(module, "__name__", "")
        if name.startswith("app.") and getattr(module, "db", None) is real_db:
            monkeypatch.setattr(module, "db", tripwire)

    def call(method: str, path: str, *, as_role: str, body: dict[str, Any] | None = None) -> _Outcome:
        _CURRENT["user"] = _user(as_role)

        async def run() -> _Outcome:
            transport = httpx.ASGITransport(app=_APP)
            async with httpx.AsyncClient(transport=transport, base_url="http://gate.test") as client:
                response = await client.request(method, f"/api{path}", json=body)
            payload = response.json() if response.content else {}
            detail = payload.get("detail", payload) if isinstance(payload, dict) else payload
            return _Outcome(reached=False, status_code=response.status_code, detail=detail)

        try:
            return asyncio.run(run())
        except _DatabaseTouched:
            return _Outcome(reached=True)

    yield SimpleNamespace(call=call, tripwire=tripwire)
    _CURRENT["user"] = None


def _code(workshop_id: str) -> str:
    prefix = f"DPW1:G:{workshop_id.upper()}"
    return f"{prefix}:{code_check(prefix)}"



def test_a_designer_may_ask(api):
    outcome = api.call(
        "POST",
        "/design-workshop-access/requests",
        as_role="DESIGNER",
        body={"workshopId": WORKSHOP_ID, "scannedCode": _code(WORKSHOP_ID)},
    )
    assert outcome.reached, outcome


def test_a_researcher_may_ask_too_because_the_role_is_checked_at_the_grant(api):
    outcome = api.call(
        "POST",
        "/design-workshop-access/requests",
        as_role="RESEARCHER",
        body={"workshopId": WORKSHOP_ID},
    )
    assert outcome.reached, outcome


@pytest.mark.parametrize(
    ("method", "path", "body"),
    [
        ("GET", "/design-workshop-access/requests", None),
        ("POST", "/design-workshop-access/requests/anything/decide", {"status": "GRANTED"}),
    ],
)
@pytest.mark.parametrize("role", ["CROWDSOURCE_VOLUNTEER", "RESEARCHER", "DESIGNER", "PROFESSOR"])
def test_only_an_admin_reads_or_decides_the_queue(api, role, method, path, body):
    outcome = api.call(method, path, as_role=role, body=body)
    assert outcome.status_code == 403, outcome
    assert api.tripwire.touched is False, "the refusal must fire before any database read"


@pytest.mark.parametrize("role", ["ADMIN", "MASTER_ADMIN"])
def test_an_admin_reaches_the_queue(api, role):
    outcome = api.call("GET", "/design-workshop-access/requests", as_role=role)
    assert outcome.reached, outcome



@pytest.mark.parametrize(
    "code",
    [
        "DPW1:G:CMGATETEST0000000000000AA:AAAA",
        "DPW1:A:CMGATETEST0000000000000AA:NEWD",
        "https://example.org/scan-me",
        "DPW1:G:LOCAL-3F2504E0-4F89-11D3-9A0C-0305E82C3301:AAAA",
        "DPW1:G:DWLOCAL-3F2504E0-4F89-11D3-9A0C-0305E82C3301:AAAA",
    ],
)
def test_a_bad_code_is_refused_before_the_database_is_asked_anything(api, code):
    outcome = api.call(
        "POST",
        "/design-workshop-access/requests",
        as_role="DESIGNER",
        body={"workshopId": WORKSHOP_ID, "scannedCode": code},
    )
    assert outcome.status_code == 422, outcome
    assert api.tripwire.touched is False, "the code refusal must not depend on a database read"


def test_a_code_for_another_workshop_is_refused_before_the_database_is_asked_anything(api):
    other = "cmgatetest0000000000000bb"
    outcome = api.call(
        "POST",
        "/design-workshop-access/requests",
        as_role="DESIGNER",
        body={"workshopId": WORKSHOP_ID, "scannedCode": _code(other)},
    )
    assert outcome.status_code == 422, outcome
    assert api.tripwire.touched is False


def test_an_unknown_status_filter_is_refused_before_the_query(api):
    outcome = api.call(
        "GET", "/design-workshop-access/requests?statusFilter=SOMEDAY", as_role="ADMIN"
    )
    assert outcome.status_code == 422, outcome
    assert "PENDING" in outcome.detail, outcome
    assert api.tripwire.touched is False


def test_an_unknown_decision_is_refused_before_the_row_is_read(api):
    for token in ("PENDING", "MAYBE", ""):
        outcome = api.call(
            "POST",
            "/design-workshop-access/requests/anything/decide",
            as_role="ADMIN",
            body={"status": token} if token else {"status": " "},
        )
        assert outcome.status_code == 422, (token, outcome)
        assert api.tripwire.touched is False, token
