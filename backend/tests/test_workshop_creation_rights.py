import asyncio
import inspect
import re
import sys
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import httpx
import pytest
from fastapi import FastAPI

import app.core.db as core_db
from app.api.router import api_router
from app.api.routes import design_workshops as design_workshop_routes, workshops as workshop_routes
from app.core import deps

REPO = Path(__file__).resolve().parents[2]

WORKSHOP_CREATE_FLOOR = 48

DESIGN_WORKSHOP_CREATORS = ("MINISTRY_ADMIN", "ADMIN", "MASTER_ADMIN")

WORKSHOP_BODY: dict[str, Any] = {
    "title": "Gate test workshop",
    "date": "2026-10-01",
    "place": "Bhuj",
    "location": {
        "latitude": 23.25,
        "longitude": 69.67,
        "state": "Gujarat",
        "district": "Kutch",
    },
}

DESIGN_WORKSHOP_BODY: dict[str, Any] = {"title": "Gate test design workshop"}


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
        return "admitted (went to write a row)" if self.reached else f"HTTP {self.status_code}: {self.detail}"


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


def _create_a_workshop(api, role: str) -> _Outcome:
    return api.call("POST", "/workshops", as_role=role, body=WORKSHOP_BODY)


def _create_a_design_workshop(api, role: str) -> _Outcome:
    return api.call("POST", "/design-workshops", as_role=role, body=DESIGN_WORKSHOP_BODY)



def test_a_designer_may_not_open_a_workshop(api):
    outcome = _create_a_workshop(api, "DESIGNER")
    assert outcome.status_code == 403, outcome


def test_the_refusal_a_designer_reads_is_a_sentence_and_names_the_next_move(api):
    detail = _create_a_workshop(api, "DESIGNER").detail
    assert detail == workshop_routes.WORKSHOP_CREATE_REFUSAL, detail
    assert "ministry" in detail.lower(), detail
    assert "Ministry Admin" in detail, detail
    assert "added to it" in detail, detail


@pytest.mark.parametrize(
    "role", sorted(r for r, rank in deps.ROLE_RANK.items() if rank < WORKSHOP_CREATE_FLOOR)
)
def test_every_tier_below_the_ministry_is_refused(api, role: str):
    outcome = _create_a_workshop(api, role)
    assert outcome.status_code == 403, outcome


@pytest.mark.parametrize("role", ["PROFESSOR", "ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR"])
def test_the_three_tiers_this_change_actually_took_it_from(api, role: str):
    outcome = _create_a_workshop(api, role)
    assert outcome.status_code == 403, outcome


def test_a_ministry_admin_opens_a_workshop(api):
    outcome = _create_a_workshop(api, "MINISTRY_ADMIN")
    assert outcome.reached, outcome


@pytest.mark.parametrize("role", ["ADMIN", "MASTER_ADMIN"])
def test_an_admin_opens_a_workshop(api, role: str):
    outcome = _create_a_workshop(api, role)
    assert outcome.reached, outcome



def test_a_designer_may_not_open_a_design_workshop(api):
    outcome = _create_a_design_workshop(api, "DESIGNER")
    assert outcome.status_code == 403, outcome


def test_the_design_workshop_refusal_is_a_sentence_too(api):
    detail = _create_a_design_workshop(api, "DESIGNER").detail
    assert detail == deps.DESIGN_WORKSHOP_CREATE_REFUSAL, detail
    assert "admin" in detail.lower(), detail
    assert "access" in detail.lower(), detail
    assert "open to you now" in detail, detail


@pytest.mark.parametrize("role", DESIGN_WORKSHOP_CREATORS)
def test_an_admin_opens_a_design_workshop(api, role: str):
    outcome = _create_a_design_workshop(api, role)
    assert outcome.reached, outcome


def test_a_ministry_admin_opens_a_design_workshop_since_the_content_admin_ruling(api):
    outcome = _create_a_design_workshop(api, "MINISTRY_ADMIN")
    assert outcome.reached, outcome



def test_the_create_gate_is_wired_to_the_route(api):
    source = inspect.getsource(workshop_routes.create_workshop)
    assert "Depends(require_workshop_opener)" in source, (
        "POST /workshops no longer declares its own create gate on the route signature"
    )
    assert "Depends(require_workshop_manager)" not in source, (
        "the create route is back on the EDIT gate — Professor and above can open workshops again"
    )


def test_the_design_workshop_create_gate_is_wired_to_its_route(api):
    source = inspect.getsource(design_workshop_routes.create_design_workshop)
    assert "assert_can_create_design_workshops(current_user)" in source


def test_correcting_a_workshop_is_still_the_professor_floor(api):
    source = inspect.getsource(workshop_routes.update_workshop)
    assert "Depends(require_workshop_manager)" in source, (
        "the EDIT gate moved with the create gate; a professor can no longer correct a workshop"
    )


def test_the_two_gates_are_different_functions_and_that_is_the_point(api):
    assert workshop_routes.require_workshop_opener is not deps.require_workshop_manager



def _kotlin_string_after(text: str, marker: str) -> str:
    after = text.split(marker, 1)[1]
    block = after.split("\n\n", 1)[0]
    return "".join(re.findall(r'"((?:[^"\\]|\\.)*)"', block))


def test_the_handset_says_exactly_what_the_server_says():
    text = (REPO / "android/app/src/main/java/com/designprototype/workshop/MainActivity.kt").read_text(
        encoding="utf-8"
    )
    kotlin = _kotlin_string_after(text, "internal const val WORKSHOP_CREATE_REFUSAL =")
    assert kotlin == workshop_routes.WORKSHOP_CREATE_REFUSAL, (
        "the handset's refusal has drifted from the server's:\n"
        f"  server:  {workshop_routes.WORKSHOP_CREATE_REFUSAL!r}\n"
        f"  handset: {kotlin!r}"
    )


def test_the_handset_asks_the_create_question_and_not_the_manage_one():
    text = (REPO / "android/app/src/main/java/com/designprototype/workshop/MainActivity.kt").read_text(
        encoding="utf-8"
    )
    assert "EntryMode.WORKSHOP -> user.canOpenAWorkshop()" in text
    assert "roleRank(role) >= RANK_MINISTRY_ADMIN" in text
    assert "canManageTheWorkshops" not in text, (
        "the old Professor-floor workshop predicate is back; a duplicate with no callers is how this "
        "defect returns"
    )
    assert "EntryMode.WORKSHOP -> if (canCreate(EntryMode.WORKSHOP)) {" in text


def test_the_web_list_page_drops_the_create_form_and_names_the_ministry():
    text = (REPO / "frontend/app/(protected)/workshops/page.tsx").read_text(encoding="utf-8")

    assert "const allowCreate = canCreateWorkshops(user);" in text
    assert "{allowManage && (editing || allowCreate) ? (" in text
    assert "Ministry Admin" in text
    assert "Ask the master admin for workshop creation access" not in text

    permissions = (REPO / "frontend/lib/permissions.ts").read_text(encoding="utf-8")
    assert "export function canCreateWorkshops(user: User | null | undefined) {" in permissions
    assert 'return hasRank(user, "MINISTRY_ADMIN");' in permissions.split(
        "export function canCreateWorkshops"
    )[1], (
        "`canCreateWorkshops` no longer mirrors `require_workshop_opener`'s MINISTRY_ADMIN floor. "
        "The server is the rule; this predicate is the courtesy that stops somebody filling in a "
        "form that ends in a 403, and looser or stricter are both wrong in ways a designer feels."
    )


def test_the_design_workshop_page_still_refuses_in_words():
    text = (REPO / "frontend/app/(protected)/design-workshops/page.tsx").read_text(encoding="utf-8")
    assert "const allowCreate = canCreateDesignWorkshops(user);" in text
    assert "DESIGN_WORKSHOP_CREATE_REFUSAL" in text
