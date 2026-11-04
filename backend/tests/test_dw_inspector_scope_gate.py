from __future__ import annotations

import asyncio
import inspect
import sys
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import httpx
import pytest
from fastapi import FastAPI

import app.core.db as core_db
import app.services.design_workshop_inspectors as inspectors
import app.services.design_workshop_viewers as viewers
from app.api.router import api_router
from app.api.routes import design_workshop_inspections as inspection_routes
from app.core import deps
from app.services import design_workshops as workshop_service

WORKSHOP_ID = "cminspectorgate000000000w"
STAGE_KEY = "WORKSHOP_SETUP"

EVERY_OTHER_TIER = (
    "CROWDSOURCE_VOLUNTEER",
    "FIELD_CONTRIBUTOR",
    "RESEARCHER",
    "DESIGNER",
    "PROFESSOR",
    "ASSISTANT_DIRECTOR",
    "REGIONAL_DIRECTOR",
    "MINISTRY_ADMIN",
    "ADMIN",
    "MASTER_ADMIN",
)
INSPECTOR = "INSPECTOR"

SERVING_ADMINS = ("MINISTRY_ADMIN", "ADMIN", "MASTER_ADMIN")

CANNOT_HOLD_AN_INSPECTION = tuple(t for t in EVERY_OTHER_TIER if t not in SERVING_ADMINS)


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

    def call(
        method: str, path: str, *, as_role: str, body: dict[str, Any] | None = None
    ) -> _Outcome:
        _CURRENT["user"] = _user(as_role)

        async def run() -> _Outcome:
            transport = httpx.ASGITransport(app=_APP)
            async with httpx.AsyncClient(transport=transport, base_url="http://gate.test") as c:
                response = await c.request(method, f"/api{path}", json=body)
            payload = response.json() if response.content else {}
            detail = payload.get("detail", payload) if isinstance(payload, dict) else payload
            return _Outcome(reached=False, status_code=response.status_code, detail=detail)

        try:
            return asyncio.run(run())
        except _DatabaseTouched:
            return _Outcome(reached=True)

    yield SimpleNamespace(call=call, tripwire=tripwire)
    _CURRENT["user"] = None



@pytest.mark.parametrize(
    "path", ["/design-workshop-inspections", f"/design-workshop-inspections/{WORKSHOP_ID}"]
)
def test_an_inspector_reaches_their_own_read_surface(api, path):
    outcome = api.call("GET", path, as_role=INSPECTOR)
    assert outcome.reached, outcome


@pytest.mark.parametrize(
    "path", ["/design-workshop-inspections", f"/design-workshop-inspections/{WORKSHOP_ID}"]
)
@pytest.mark.parametrize("role", CANNOT_HOLD_AN_INSPECTION)
def test_every_tier_that_cannot_hold_an_inspection_is_refused_the_surface(api, path, role):
    outcome = api.call("GET", path, as_role=role)
    assert outcome.status_code == 403, outcome
    assert api.tripwire.touched is False, "the refusal must fire before any database read"
    assert "/api/design-workshops" not in outcome.detail, (
        "the refusal is field copy and must not name an API path; see NOT_AN_INSPECTOR_DETAIL"
    )
    assert outcome.detail == (
        "This page is for inspectors. Sign out and back in, or ask an administrator."
    )


@pytest.mark.parametrize(
    "path", ["/design-workshop-inspections", f"/design-workshop-inspections/{WORKSHOP_ID}"]
)
@pytest.mark.parametrize("role", SERVING_ADMINS)
def test_an_administrator_reaches_the_surface_and_the_rows_decide_what_they_see(api, path, role):
    outcome = api.call("GET", path, as_role=role)
    assert outcome.reached, outcome


@pytest.mark.parametrize(
    ("method", "body"),
    [("GET", None), ("PUT", {"userIds": []})],
)
@pytest.mark.parametrize(
    "role",
    [
        "RESEARCHER",
        "DESIGNER",
        "PROFESSOR",
        INSPECTOR,
        "ASSISTANT_DIRECTOR",
        "REGIONAL_DIRECTOR",
    ],
)
def test_only_an_assigner_reads_or_writes_the_inspection_roster(api, role, method, body):
    outcome = api.call(
        method, f"/design-workshop-inspections/{WORKSHOP_ID}/inspectors", as_role=role, body=body
    )
    assert outcome.status_code == 403, outcome
    assert api.tripwire.touched is False, "the refusal must fire before any database read"


@pytest.mark.parametrize("role", ["MINISTRY_ADMIN", "ADMIN", "MASTER_ADMIN"])
def test_an_assigner_reaches_the_inspection_roster_and_the_picker(api, role):
    outcome = api.call(
        "GET", f"/design-workshop-inspections/{WORKSHOP_ID}/inspectors", as_role=role
    )
    assert outcome.reached, outcome
    picker = api.call("GET", "/design-workshop-inspections/eligible-inspectors", as_role=role)
    assert picker.reached, picker


def test_the_literal_path_is_not_swallowed_by_the_workshop_id_route(api):
    refused = api.call("GET", "/design-workshop-inspections/eligible-inspectors", as_role=INSPECTOR)
    assert refused.status_code == 403, refused
    assert api.tripwire.touched is False
    admitted = api.call("GET", f"/design-workshop-inspections/{WORKSHOP_ID}", as_role=INSPECTOR)
    assert admitted.reached, admitted
    outcome = api.call("GET", "/design-workshop-inspections/eligible-inspectors", as_role="ADMIN")
    assert outcome.reached, outcome



WRITE_DOORS: tuple[tuple[str, str, dict[str, Any] | None], ...] = (
    ("PATCH", f"/design-workshops/{WORKSHOP_ID}", {"title": "Renamed by an inspector"}),
    ("PUT", f"/design-workshops/{WORKSHOP_ID}/stages/{STAGE_KEY}", {"entries": []}),
    ("PUT", f"/design-workshops/{WORKSHOP_ID}/custom-sections", {"sections": []}),
    (
        "POST",
        f"/design-workshops/{WORKSHOP_ID}/ai-layers",
        {"sourceMediaId": "cminspectorgate000000000m"},
    ),
    ("POST", f"/design-workshops/{WORKSHOP_ID}/dictation-consent", {"decision": "GRANTED"}),
    ("POST", "/design-workshops", {"title": "A workshop an inspector started"}),
)


@pytest.mark.parametrize(("method", "path", "body"), WRITE_DOORS)
def test_an_inspector_is_refused_every_write_door_before_the_database(api, method, path, body):
    outcome = api.call(method, path, as_role=INSPECTOR, body=body)
    assert outcome.status_code == 403, outcome
    assert api.tripwire.touched is False, (
        "the refusal must be decided from the caller's role alone; a gate that runs after the "
        "loader is a gate somebody can reorder without noticing"
    )


INSPECTOR_WRITE_DOORS = {
    ("POST", "/design-workshop-inspections/{workshop_id}/feedback"),
    ("POST", "/design-workshop-inspections/{workshop_id}/send-back"),
    ("POST", "/design-workshop-inspections/{workshop_id}/feedback/batch"),
    ("POST", "/design-workshop-inspections/{workshop_id}/send-back/batch"),
    ("POST", "/design-workshop-inspections/{workshop_id}/clearance"),
    ("DELETE", "/design-workshop-inspections/{workshop_id}/clearance"),
}


def test_the_inspection_surface_offers_only_the_two_write_doors_it_is_allowed(api):
    for route in inspection_routes.router.routes:
        gates = _dependency_names(route)
        assert gates & {"require_inspector", "require_workshop_assigner"}, (
            f"{route.path} is on the inspection router with neither door; every route here is "
            f"either the inspector's own surface or the assigner's administration of it"
        )
        if "require_inspector" not in gates:
            continue
        for method in set(route.methods):
            if method == "GET":
                continue
            assert (method, route.path) in INSPECTOR_WRITE_DOORS, (
                f"{method} {route.path} is reachable by an INSPECTOR and is neither a GET nor one "
                f"of the two doors this surface is allowed. Read the header of "
                f"app/services/design_workshop_inspectors.py, then add it to INSPECTOR_WRITE_DOORS "
                f"with the reason — an inspection is a read and a note, and nothing else."
            )


def test_both_allowed_write_doors_actually_exist(api):
    live = {
        (method, route.path)
        for route in inspection_routes.router.routes
        for method in set(route.methods)
        if method != "GET" and "require_inspector" in _dependency_names(route)
    }
    assert live == INSPECTOR_WRITE_DOORS


def test_an_inspector_still_writes_no_workshop_content(api):
    from app.schemas import design_workshop_review_loop as loop

    assert {"DesignWorkshop", "DwInspectionFeedback", "ReviewLog"} == loop.WRITABLE_TABLES
    assert "DwStageEntry" not in loop.WRITABLE_TABLES


def _dependency_names(route: Any) -> set[str]:
    seen: set[str] = set()
    stack = [route.dependant]
    while stack:
        node = stack.pop()
        if node.call is not None:
            seen.add(getattr(node.call, "__name__", ""))
        stack.extend(node.dependencies)
    return seen


def test_the_read_only_loader_has_no_for_edit_parameter():
    ours = inspect.signature(inspectors.load_inspectable_workshop_or_404).parameters
    assert "for_edit" not in ours, (
        "load_inspectable_workshop_or_404 has grown a for_edit parameter. That is the ONE change "
        "app/services/design_workshop_inspectors.py refuses; read its header."
    )
    theirs = inspect.signature(workshop_service.load_workshop_or_404).parameters
    assert "for_edit" in theirs, "the control: the sibling loader is still the one that edits"



class _Rows:
    def __init__(self, rows: list[Any] | None = None) -> None:
        self.rows = list(rows or [])

    @staticmethod
    def _matches(row: Any, where: dict[str, Any]) -> bool:
        for key, wanted in where.items():
            if key.endswith("_userId"):
                return _Rows._matches(row, wanted)
            if getattr(row, key, None) != wanted:
                return False
        return True

    async def find_unique(self, where: dict[str, Any], include: Any = None) -> Any:
        return next((row for row in self.rows if self._matches(row, where)), None)

    async def find_first(self, where: dict[str, Any], include: Any = None) -> Any:
        return await self.find_unique(where, include)


class _Client:
    def __init__(self, **tables: _Rows) -> None:
        for name, table in tables.items():
            setattr(self, name, table)


VIEWER_ID = "cminspectorgate000000000v"
INSPECTOR_ID = "cminspectorgate000000000i"
STRANGER_ID = "cminspectorgate000000000s"


@pytest.fixture
def world(monkeypatch: pytest.MonkeyPatch):
    tables = {
        "designworkshopviewer": _Rows(
            [SimpleNamespace(designWorkshopId=WORKSHOP_ID, userId=VIEWER_ID)]
        ),
        "designworkshopinspector": _Rows(
            [SimpleNamespace(designWorkshopId=WORKSHOP_ID, userId=INSPECTOR_ID)]
        ),
    }
    client = _Client(**tables)
    for module in (viewers, inspectors):
        monkeypatch.setattr(module, "db", client)
    return SimpleNamespace(db=client, **tables)


def test_an_inspection_row_does_not_satisfy_has_viewer_grant(world):
    assert world.designworkshopinspector.rows, "the fixture must actually hold an inspection"
    assert asyncio.run(viewers.has_viewer_grant(WORKSHOP_ID, INSPECTOR_ID)) is False
    assert asyncio.run(viewers.has_viewer_grant(WORKSHOP_ID, VIEWER_ID)) is True


def test_a_viewer_row_does_not_satisfy_has_inspection_scope(world):
    assert world.designworkshopviewer.rows, "the fixture must actually hold a viewer"
    assert asyncio.run(inspectors.has_inspection_scope(WORKSHOP_ID, VIEWER_ID)) is False
    assert asyncio.run(inspectors.has_inspection_scope(WORKSHOP_ID, INSPECTOR_ID)) is True


def test_a_stranger_holds_neither(world):
    assert asyncio.run(viewers.has_viewer_grant(WORKSHOP_ID, STRANGER_ID)) is False
    assert asyncio.run(inspectors.has_inspection_scope(WORKSHOP_ID, STRANGER_ID)) is False



OVERSEER_ID = "cminspectorgate0000000d"


@pytest.fixture
def sixth(world, monkeypatch: pytest.MonkeyPatch):
    from app.services import design_workshop_oversight as oversight

    table = _Rows([SimpleNamespace(designWorkshopId=WORKSHOP_ID, userId=OVERSEER_ID)])
    world.db.designworkshopoversight = table
    monkeypatch.setattr(oversight, "db", world.db)
    return SimpleNamespace(oversight=oversight, table=table)


def test_an_oversight_row_does_not_satisfy_has_inspection_scope(sixth):
    assert sixth.table.rows, "the fixture must actually hold an oversight row"
    assert asyncio.run(inspectors.has_inspection_scope(WORKSHOP_ID, OVERSEER_ID)) is False
    assert asyncio.run(inspectors.has_inspection_scope(WORKSHOP_ID, INSPECTOR_ID)) is True


def test_an_inspection_row_does_not_satisfy_has_oversight_scope(world, sixth):
    assert world.designworkshopinspector.rows, "the fixture must actually hold an inspection"
    assert asyncio.run(sixth.oversight.has_oversight_scope(WORKSHOP_ID, INSPECTOR_ID)) is False
    assert asyncio.run(sixth.oversight.has_oversight_scope(WORKSHOP_ID, OVERSEER_ID)) is True


def test_a_viewer_row_does_not_satisfy_has_oversight_scope_either(sixth):
    assert asyncio.run(sixth.oversight.has_oversight_scope(WORKSHOP_ID, VIEWER_ID)) is False
    assert asyncio.run(viewers.has_viewer_grant(WORKSHOP_ID, VIEWER_ID)) is True


def test_the_two_clauses_are_different_expressions_over_different_relations():
    theirs = viewers.visible_to_clause(INSPECTOR_ID)
    ours = inspectors.inspectable_by_clause(INSPECTOR_ID)
    assert ours == {"inspectors": {"some": {"userId": INSPECTOR_ID}}}
    assert "viewers" not in repr(ours), "the inspection clause must never read the viewer relation"
    assert "inspectors" not in repr(theirs), "and the viewer clause must never read this one"
    assert "createdById" not in repr(ours), (
        "an inspector creates nothing; a createdById arm here would be a second way into the scope"
    )


def test_the_tier_stays_out_of_the_designer_set_and_the_holders_overlap_it_by_ruling():
    assert frozenset({INSPECTOR}) == inspectors.INSPECTION_ROLES
    assert INSPECTOR not in deps.DESIGN_WORKSHOP_ROLES, (
        "INSPECTOR must stay out of DESIGN_WORKSHOP_ROLES, which is 'the people who sign the "
        "report'. Adding it hands the tier all eighteen _require_designer routes at once."
    )
    assert frozenset({INSPECTOR, *SERVING_ADMINS}) == inspectors.INSPECTION_HOLDER_ROLES
    overlap = inspectors.INSPECTION_HOLDER_ROLES & deps.DESIGN_WORKSHOP_ROLES
    assert overlap == frozenset(SERVING_ADMINS)

    assert inspectors.is_inspector(_user(INSPECTOR)) is True
    assert inspectors.may_hold_an_inspection(_user(INSPECTOR)) is True
    for role in EVERY_OTHER_TIER:
        assert inspectors.is_inspector(_user(role)) is False, role
        assert inspectors.may_hold_an_inspection(_user(role)) is (role in SERVING_ADMINS), role



BACKEND = Path(__file__).resolve().parents[1]
APP = BACKEND / "app"

THE_FEATURE = {
    APP / "services" / "design_workshop_inspectors.py",
    APP / "api" / "routes" / "design_workshop_inspections.py",
    APP / "schemas" / "design_workshop_inspections.py",
    APP / "services" / "weekly_digest.py",
}

CLASSIFIED_READERS: dict[Path, frozenset[str]] = {
    APP / "services" / "ministry_dashboard.py": frozenset({"designworkshopinspector"}),
    APP / "services" / "design_workshop_review_aids.py": frozenset({"designworkshopinspector"}),
}

THE_NAMES = (
    "has_inspection_scope",
    "inspectable_by_clause",
    "load_inspectable_workshop_or_404",
    "designworkshopinspector",
)


def test_no_other_module_names_the_inspection_predicates():
    offenders: list[str] = []
    for path in sorted(APP.rglob("*.py")):
        if path in THE_FEATURE:
            continue
        permitted = CLASSIFIED_READERS.get(path, frozenset())
        text = path.read_text(encoding="utf-8", errors="replace")
        lowered = text.lower()
        for name in THE_NAMES:
            if name in permitted:
                continue
            if name in lowered:
                offenders.append(f"{path.relative_to(BACKEND)} names {name!r}")
    assert not offenders, (
        "the inspection scope has been wired into another path:\n  "
        + "\n  ".join(offenders)
        + "\nRead the header of app/services/design_workshop_inspectors.py before allowing it."
    )


def test_a_classified_reader_speaks_the_table_and_none_of_the_predicates():
    for path, permitted in CLASSIFIED_READERS.items():
        assert path.exists(), f"{path.relative_to(BACKEND)} is classified and does not exist"
        lowered = path.read_text(encoding="utf-8", errors="replace").lower()
        for name in permitted:
            assert name in lowered, (
                f"{path.relative_to(BACKEND)} is classified for {name!r} and no longer names it. "
                "Delete the row: a classification that outlived its call site is a hole in the "
                "sweep that nobody can see."
            )
        for name in THE_NAMES:
            if name in permitted:
                continue
            assert name not in lowered, (
                f"{path.relative_to(BACKEND)} is a CLASSIFIED READER of "
                f"{sorted(permitted)} and it now also names {name!r}. That is a predicate: it "
                "decides who may reach a workshop. Reading a table to count rows and consulting a "
                "scope to grant access are different acts, and this file was only ever allowed the "
                "first. Read the header of app/services/design_workshop_inspectors.py."
            )


def test_the_sweep_actually_reaches_the_modules_it_is_defending():
    seen = {p.relative_to(BACKEND).as_posix() for p in APP.rglob("*.py")}
    for expected in (
        "app/services/design_workshops.py",
        "app/services/design_workshop_viewers.py",
        "app/services/records.py",
        "app/api/routes/questionnaire_forms.py",
    ):
        assert expected in seen, f"the sweep never reached {expected}"
    assert set(APP.rglob("*.py")) >= THE_FEATURE, "THE_FEATURE names a file that does not exist"
