"""**AN INSPECTION IS A READ AND NOTHING ELSE.** The tripwire for that. No database required.

WHAT THIS FILE IS DEFENDING, AND WHY IT IS A WHOLE MODULE FOR ONE IDEA
=====================================================================

The INSPECTOR tier sits between DESIGNER (35) and PROFESSOR (40) and inspects a designer's work
without running workshops. The obvious way to give it access to a workshop is a
``DesignWorkshopViewer`` row, or a ``level`` column on one — and it was designed and rejected,
because **a viewer row confers STAGE WRITES**. ``load_workshop_or_404(..., for_edit=True)`` performs
no role check whatsoever: the creator, an admin, or ANY viewer grantee passes, and that one helper
is what FOURTEEN write routes pair with ``_require_designer``, what the export ledger stands behind
alone, and what the report route stands behind alone. (Fourteen, not the eighteen this paragraph
first said: eighteen is every route ``_require_designer`` guards, two of which are GET allowance
probes that write nothing and never touch the loader. The fourteen are named and counted at the top
of ``app/services/design_workshop_inspectors.py``.)

So the design is a SEPARATE TABLE that no write path consults, reached through a SEPARATE loader
that has no ``for_edit`` parameter to pass. This module is the assertion that keeps that true. It is
the mirror image of ``test_design_workshop_provisional_isolation``, which asserts that a foothold is
a stranger to every READ gate; this asserts that an inspection is a stranger to every WRITE gate.

**IT RUNS IN CI, AND THAT IS THE POINT OF ITS SHAPE.** ``test_dw_inspector_scope`` needs Postgres
and skips itself wherever there is not a local one — which is every CI run, deliberately, because
the deployed database is not a scratch pad. It ALSO skips until the INSPECTOR tier reaches
``ROLE_RANK``, which is a second lane's work. So the assertions that an inspector cannot write would
never be made on the one machine that gates a merge. They are made here instead, over the real
routers, with ``db`` replaced by a tripwire — and they are correct whether or not the tier has
landed, because nothing here reads a database or an enum.

FOUR THINGS ARE PINNED, and each is a way this scope could ship looking finished while being wrong.

1. **THE DOORS.** An inspector reaches their own read surface; everybody else — INCLUDING ADMINS —
   is refused there with a sentence naming the route they actually want. The administration of who
   inspects what is admin-only, and an INSPECTOR is refused it: the inspected must not choose the
   inspector, and neither may the inspector choose themselves.

2. **THE WRITE REFUSALS, EACH ASSERTED RATHER THAN DOCUMENTED.** Six write doors on
   ``/design-workshops`` refuse an inspector BEFORE any database read. "We never built the UI for
   it" is not enforcement, and a scope whose limits are untested is a scope that will quietly widen.

3. **THE TWO PREDICATES CANNOT SEE EACH OTHER.** An inspection row does not satisfy
   ``has_viewer_grant`` (the chokepoint four read-and-write paths consult) and a viewer row does not
   satisfy ``has_inspection_scope``. Asserted against the REAL functions over fake tables, so it
   stays an assertion about the codebase rather than about this file.

4. **NOTHING OUTSIDE THE FEATURE NAMES ITS PREDICATES.** A source sweep. The day somebody writes
   ``or await has_inspection_scope(...)`` beside a ``has_viewer_grant`` call — which is the single
   most plausible way this scope becomes a write grant — this is what goes red.

IF YOU ARE HERE BECAUSE THIS MODULE WENT RED after "simplifying" the inspection into a flag on the
viewer row: that is what it is for. The security property was in the separation.
"""

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

#: A cuid-shaped id that names nothing. Nothing in the first two sections reaches a database, so it
#: never needs to.
WORKSHOP_ID = "cminspectorgate000000000w"
STAGE_KEY = "WORKSHOP_SETUP"

#: Every tier the ladder holds today, plus the one this feature is for. Written as a literal rather
#: than read from ``ROLE_RANK`` ON PURPOSE, and this is the one place in the repository where that is
#: the right call: these strings are the INPUT to a gate, and the gates under test are SET
#: MEMBERSHIP, so a list derived from the ladder would stop exercising INSPECTOR on a deployment
#: where the tier has not landed — which is exactly the deployment this file has to be correct on.
#: ``tests/test_role_ladder_parity`` is what keeps the ladder's own copies honest; this is not a
#: mirror of it.
EVERY_OTHER_TIER = (
    "CROWDSOURCE_VOLUNTEER",
    "FIELD_CONTRIBUTOR",
    "RESEARCHER",
    "DESIGNER",
    "PROFESSOR",
    "ADMIN",
    "MASTER_ADMIN",
)
INSPECTOR = "INSPECTOR"


class _DatabaseTouched(Exception):
    """Raised by the tripwire. Escaping the request means the handler got past every guard."""


class _Tripwire:
    """Stands in for ``db``. Reading any delegate off it means a database read was about to happen."""

    def __init__(self) -> None:
        object.__setattr__(self, "touched", False)

    def __getattr__(self, name: str) -> Any:
        # ``__getattr__`` and not ``__getattribute__``, so ``touched`` above stays readable.
        object.__setattr__(self, "touched", True)
        raise _DatabaseTouched(name)


class _Outcome:
    """Either "a read was attempted" or "the request was answered", never a bare status code.

    The distinction is the whole point of the first two sections: a 403 that reached the database and
    a 403 that did not are the same three digits and two different properties. Here it is sharper
    still — "reached" is how the ADMITTING cases are asserted, because "not 403" would also be
    satisfied by a 422 from an unrelated cause.
    """

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


#: Assembled once. Every router with its response models costs a couple of seconds to build, and
#: nothing request-scoped lives on it — the caller comes from ``_CURRENT``, the database from the
#: fixture.
_APP = _build_app()


def _user(role: str) -> SimpleNamespace:
    """A user row as ``require_admin``, ``is_admin`` and ``role_value`` read one."""
    return SimpleNamespace(
        id=f"u-{role.lower()}", email=f"{role.lower()}@example.test", name="Gate Test", role=role
    )


@pytest.fixture
def api(monkeypatch: pytest.MonkeyPatch):
    """The real API with every module's ``db`` rebound to the tripwire.

    Rebinding BY IDENTITY rather than by module name: the modules do ``from app.core.db import db``,
    so each holds its own reference, and patching only ``app.core.db`` would leave every one of them
    pointing at the real client. Finding them by identity also keeps working when a module is added —
    which matters here, because this feature added three.

    THE HARNESS IS BORROWED FROM ``test_design_workshop_access_gate`` AND DELIBERATELY RE-TYPED
    RATHER THAN IMPORTED, on that module's own written reasoning: a test module that imports another
    test module's harness makes two files fail together for one cause and makes neither runnable on
    its own.
    """
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


# --------------------------------------------------------------------------------------
# 1. THE DOORS
# --------------------------------------------------------------------------------------


@pytest.mark.parametrize(
    "path", ["/design-workshop-inspections", f"/design-workshop-inspections/{WORKSHOP_ID}"]
)
def test_an_inspector_reaches_their_own_read_surface(api, path):
    """Open to the tier, and reaching the database is what proves it.

    "Not 403" would also be satisfied by a 422 from an unrelated cause, which is why the outcome here
    is "a read was attempted" rather than a status code. What that read then ANSWERS — an empty page
    for an inspector with no scope row, a 404 for a workshop they were not assigned — is asserted
    over a real database in ``test_dw_inspector_scope``.
    """
    outcome = api.call("GET", path, as_role=INSPECTOR)
    assert outcome.reached, outcome


@pytest.mark.parametrize(
    "path", ["/design-workshop-inspections", f"/design-workshop-inspections/{WORKSHOP_ID}"]
)
@pytest.mark.parametrize("role", EVERY_OTHER_TIER)
def test_everybody_else_is_refused_the_inspection_surface_including_admins(api, path, role):
    """403 for every other tier, decided before any database read.

    **ADMIN AND MASTER_ADMIN ARE IN THIS LIST ON PURPOSE**, which is the row a reader will want to
    argue with. Admitting them would mean one of two things and both are worse than a refusal:
    scoped by their OWN inspection rows an admin sees an empty list and reads it as a broken
    deployment, and scoped by "everything, because they are an admin" this surface silently becomes a
    second full read of every workshop in the repository — a second place to look when somebody has
    access they should not.

    So the refusal NAMES THE OTHER DOOR, and that sentence is asserted rather than left to a code
    review: an admin told only "forbidden" on a READ surface will reasonably conclude something is
    broken and file a bug against it.
    """
    outcome = api.call("GET", path, as_role=role)
    assert outcome.status_code == 403, outcome
    assert api.tripwire.touched is False, "the refusal must fire before any database read"
    assert "/api/design-workshops" in outcome.detail, (
        "the refusal has to name the route this caller actually wants; see NOT_AN_INSPECTOR_DETAIL"
    )


@pytest.mark.parametrize(
    ("method", "body"),
    [("GET", None), ("PUT", {"userIds": []})],
)
@pytest.mark.parametrize("role", ["RESEARCHER", "DESIGNER", "PROFESSOR", INSPECTOR])
def test_only_an_admin_reads_or_writes_the_inspection_roster(api, role, method, body):
    """403 for everybody below admin, and the database never touched.

    **THE INSPECTOR IS IN THIS LIST AND IT IS THE MOST IMPORTANT ROW.** An inspector who could write
    this roster could assign themselves to any workshop in the repository, which turns a scope an
    admin controls into one its holder does. DESIGNER is the second most important: the inspected
    must not choose the inspector, or the inspection is worth nothing — that is the entire value of
    an independent review and it is enforced here rather than by there being no button for it.

    PROFESSOR is here for the reason the sibling queue's test gives: they outrank a designer on the
    ladder and are still not an administrator, which is the row a rank comparison written in place of
    ``require_admin`` would get wrong.
    """
    outcome = api.call(
        method, f"/design-workshop-inspections/{WORKSHOP_ID}/inspectors", as_role=role, body=body
    )
    assert outcome.status_code == 403, outcome
    assert api.tripwire.touched is False, "the refusal must fire before any database read"


@pytest.mark.parametrize("role", ["ADMIN", "MASTER_ADMIN"])
def test_an_admin_reaches_the_inspection_roster_and_the_picker(api, role):
    outcome = api.call(
        "GET", f"/design-workshop-inspections/{WORKSHOP_ID}/inspectors", as_role=role
    )
    assert outcome.reached, outcome
    picker = api.call("GET", "/design-workshop-inspections/eligible-inspectors", as_role=role)
    assert picker.reached, picker


def test_the_literal_path_is_not_swallowed_by_the_workshop_id_route(api):
    """``/eligible-inspectors`` must not be read as a workshop id.

    ``GET /design-workshop-inspections/{workshop_id}`` matches that string perfectly well and would
    answer 403-then-404 for an admin, which is the trap that once left the admin's DESIGNER picker
    empty on a server where the route existed. Asserted through the OUTCOME rather than by reading
    declaration order, so it stays true however the module is reorganised: an admin reaching the
    picker proves the literal route won, because an admin is refused the ``{workshop_id}`` route
    before it can touch anything.
    """
    outcome = api.call("GET", "/design-workshop-inspections/eligible-inspectors", as_role="ADMIN")
    assert outcome.reached, outcome
    # The control: the same admin on the id-shaped path IS refused, so the assertion above is the
    # literal route matching rather than admins being admitted to everything on this prefix.
    refused = api.call("GET", f"/design-workshop-inspections/{WORKSHOP_ID}", as_role="ADMIN")
    assert refused.status_code == 403, refused


# --------------------------------------------------------------------------------------
# 2. THE WRITE REFUSALS — what the scope does NOT carry
# --------------------------------------------------------------------------------------


#: The write doors on ``/design-workshops`` that answer BEFORE any database read, because
#: ``_require_designer`` (or ``assert_can_create_design_workshops``) is the first statement of the
#: handler. Every one of them is a thing the module docstring of
#: ``services/design_workshop_inspectors`` claims an inspection row does not carry.
#:
#: THE THREE THAT ARE NOT HERE ARE NOT MISSING. ``DELETE /{id}``, ``POST /{id}/report`` and
#: ``POST /{id}/exports`` all call ``load_workshop_or_404`` FIRST and gate afterwards (or, for the
#: report, not at all — it is open to anyone who may READ the workshop). So they cannot be refused
#: from the request alone: they are refused by that loader answering 404 to an account with no viewer
#: grant, which is a fact about a database and is asserted in ``test_dw_inspector_scope``.
#: EVERY BODY HERE IS VALID, and that is load-bearing rather than tidy. These schemas are
#: ``extra="forbid"``, so a body with a stray key is answered 422 by Pydantic BEFORE the handler
#: runs — and a 422 would make each of these tests pass for the wrong reason for ever, asserting
#: nothing about the role at all. Minimal-but-valid is the shape that reaches the gate.
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
    """**THE ASSERTION THE WHOLE FEATURE RESTS ON**, six times over.

    READ-ONLY IS THE POINT, and a scope whose limits are untested is a scope that will quietly
    widen. Each of these is a thing a viewer row DOES carry and an inspection row deliberately does
    not: renaming the workshop, saving any of the 22 stages, redefining the custom sections,
    registering an AI layer, recording the artisan's Tier-3 consent, and starting a workshop at all.

    403 AND THE DATABASE NEVER TOUCHED. The status code alone would be satisfied by a refusal that
    ran after a lookup, and that is a materially weaker property: a gate placed after the loader can
    be reordered by somebody who only reads the status code, and the loader is precisely the thing
    that grants stage writes to whoever it admits.

    Note what is NOT asserted here: that these routes refuse an inspector *because* of the role. They
    refuse because ``can_run_design_workshops`` is a SET — ``{DESIGNER, ADMIN, MASTER_ADMIN}`` — and
    INSPECTOR is not in it, which is the same reason PROFESSOR at rank 40 is refused. That is the
    trap this tier was designed around: a rank inserted between 35 and 40 buys nothing here, so the
    scope had to be built rather than inherited.
    """
    outcome = api.call(method, path, as_role=INSPECTOR, body=body)
    assert outcome.status_code == 403, outcome
    assert api.tripwire.touched is False, (
        "the refusal must be decided from the caller's role alone; a gate that runs after the "
        "loader is a gate somebody can reorder without noticing"
    )


def test_the_inspection_surface_offers_no_write_door_of_its_own(api):
    """Every route an inspector can reach is a GET, checked over the router rather than by reading.

    Walks the real dependency tree: a route gated by :func:`require_inspector` is one an inspector
    can reach, and every one of those must be a GET. A route gated by ``require_admin`` may be
    anything, because an admin is who administers this.

    THIS IS THE ASSERTION THAT SURVIVES A NEW ROUTE. The parametrised refusals above cover the doors
    that exist today; this one fails when somebody adds a POST to this router and hangs it on the
    inspector's dependency — which is how a read-only surface acquires its first write.
    """
    for route in inspection_routes.router.routes:
        gates = _dependency_names(route)
        assert gates & {"require_inspector", "require_admin"}, (
            f"{route.path} is on the inspection router with neither door; every route here is "
            f"either the inspector's own read surface or the admin's administration of it"
        )
        if "require_inspector" in gates:
            assert set(route.methods) == {"GET"}, (
                f"{route.methods} {route.path} is reachable by an INSPECTOR and is not a GET. "
                f"Read the header of app/services/design_workshop_inspectors.py before changing "
                f"this test: read-only here is structural, not a policy note."
            )


def _dependency_names(route: Any) -> set[str]:
    """Every dependency callable in one route's tree, by name."""
    seen: set[str] = set()
    stack = [route.dependant]
    while stack:
        node = stack.pop()
        if node.call is not None:
            seen.add(getattr(node.call, "__name__", ""))
        stack.extend(node.dependencies)
    return seen


def test_the_read_only_loader_has_no_for_edit_parameter():
    """**THE STRUCTURAL PROPERTY, AS ONE LINE.**

    ``design_workshops.load_workshop_or_404`` takes ``for_edit`` and performs NO role check —
    creator, admin or any viewer grantee passes — so a predicate added to it is a write grant
    whatever it is named. The inspector's loader is a different function that cannot express the
    same thing: there is no argument an inspector's request could carry that turns its read into a
    write, because there is no such argument.

    The control below is not decoration: without it this assertion would pass just as happily if
    somebody deleted ``for_edit`` from the sibling, which would be a far larger change than this
    test is about.
    """
    ours = inspect.signature(inspectors.load_inspectable_workshop_or_404).parameters
    assert "for_edit" not in ours, (
        "load_inspectable_workshop_or_404 has grown a for_edit parameter. That is the ONE change "
        "app/services/design_workshop_inspectors.py refuses; read its header."
    )
    theirs = inspect.signature(workshop_service.load_workshop_or_404).parameters
    assert "for_edit" in theirs, "the control: the sibling loader is still the one that edits"


# --------------------------------------------------------------------------------------
# 3. THE TWO PREDICATES CANNOT SEE EACH OTHER
# --------------------------------------------------------------------------------------


class _Rows:
    """A delegate over a list, narrow enough that an unexpected clause fails loudly."""

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


class _Client:
    def __init__(self, **tables: _Rows) -> None:
        for name, table in tables.items():
            setattr(self, name, table)


VIEWER_ID = "cminspectorgate000000000v"
INSPECTOR_ID = "cminspectorgate000000000i"
STRANGER_ID = "cminspectorgate000000000s"


@pytest.fixture
def world(monkeypatch: pytest.MonkeyPatch):
    """One workshop with a co-designer on it and an inspector assigned to it.

    Both modules that hold their own ``db`` reference are rebound, because the REAL predicates are
    what read these fake tables. Stubbing the predicates instead would assert nothing: the property
    is what those two functions do when the other's row exists, and a stub would just be this file
    agreeing with itself.
    """
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
    """**THE SINGLE MOST IMPORTANT ASSERTION IN THIS FEATURE.**

    ``has_viewer_grant`` is consulted from four places — ``load_workshop_or_404``, ``design_ratings``,
    ``routes/questionnaire_forms`` and ``design_workshop_access`` — and the first of those is the
    loader fourteen write routes pair with ``_require_designer`` and pass ``for_edit=True``. If an
    inspection row satisfied it, an account whose whole purpose is to READ would hold stage writes on
    a fortnight of somebody else's fieldwork.

    The inspection is a row in a DIFFERENT TABLE, so the predicate cannot see it and does not have to
    be taught not to. That is the whole design, and the assertion is written against the real
    function so it stays an assertion about the codebase.
    """
    assert world.designworkshopinspector.rows, "the fixture must actually hold an inspection"
    assert asyncio.run(viewers.has_viewer_grant(WORKSHOP_ID, INSPECTOR_ID)) is False
    # The control: a real viewer row does satisfy it, so the assertion above is not passing because
    # the predicate is broken for everybody.
    assert asyncio.run(viewers.has_viewer_grant(WORKSHOP_ID, VIEWER_ID)) is True


def test_a_viewer_row_does_not_satisfy_has_inspection_scope(world):
    """The other direction, and it is not symmetry for its own sake.

    A co-designer holding a viewer row must not silently appear on the inspection surface: that
    surface is scoped to people an ADMIN assigned to examine this workshop, and a designer who
    wandered onto it would be reading a screen that says their own work is under review by them.
    More concretely, it would make ``GET /design-workshop-inspections`` a second, differently-shaped
    read of the same workshops — two places to look when somebody has access they should not.
    """
    assert world.designworkshopviewer.rows, "the fixture must actually hold a viewer"
    assert asyncio.run(inspectors.has_inspection_scope(WORKSHOP_ID, VIEWER_ID)) is False
    assert asyncio.run(inspectors.has_inspection_scope(WORKSHOP_ID, INSPECTOR_ID)) is True


def test_a_stranger_holds_neither(world):
    """The control for both: nothing here is answering yes to everybody."""
    assert asyncio.run(viewers.has_viewer_grant(WORKSHOP_ID, STRANGER_ID)) is False
    assert asyncio.run(inspectors.has_inspection_scope(WORKSHOP_ID, STRANGER_ID)) is False


def test_the_two_clauses_are_different_expressions_over_different_relations():
    """``inspectable_by_clause`` mirrors ``visible_to_clause`` in SHAPE and in nothing else.

    Both are AND-composable relation filters, which is what lets a reader carry one function's
    reasoning to the other. What must never converge is WHAT THEY READ: the viewer clause has a
    ``createdById`` arm because a designer holds their own workshop, and the inspection clause
    deliberately has one source and no fallback, so an inspector with no row sees nothing at all.
    """
    theirs = viewers.visible_to_clause(INSPECTOR_ID)
    ours = inspectors.inspectable_by_clause(INSPECTOR_ID)
    assert ours == {"inspectors": {"some": {"userId": INSPECTOR_ID}}}
    assert "viewers" not in repr(ours), "the inspection clause must never read the viewer relation"
    assert "inspectors" not in repr(theirs), "and the viewer clause must never read this one"
    assert "createdById" not in repr(ours), (
        "an inspector creates nothing; a createdById arm here would be a second way into the scope"
    )


def test_the_two_role_sets_stay_disjoint():
    """An account must never be eligible to hold BOTH a viewer row and an inspection row.

    A viewer row carries stage WRITES; an inspection row is read-only. One account eligible for both
    on one workshop is the contradiction the whole feature is built to prevent — and it is what makes
    ``_assert_every_id_may_inspect``'s "already on this workshop" refusal a backstop rather than the
    only guard. The module raises at import if this ever stops being true; this says so where a
    reader will find it.
    """
    assert not (inspectors.INSPECTION_ROLES & deps.DESIGN_WORKSHOP_ROLES)
    assert INSPECTOR not in deps.DESIGN_WORKSHOP_ROLES, (
        "INSPECTOR must stay out of DESIGN_WORKSHOP_ROLES, which is 'the people who sign the "
        "report'. Adding it hands the tier all eighteen _require_designer routes at once."
    )
    assert inspectors.is_inspector(_user(INSPECTOR)) is True
    for role in EVERY_OTHER_TIER:
        assert inspectors.is_inspector(_user(role)) is False, role


# --------------------------------------------------------------------------------------
# 4. NOTHING OUTSIDE THE FEATURE NAMES ITS PREDICATES
# --------------------------------------------------------------------------------------


BACKEND = Path(__file__).resolve().parents[1]
APP = BACKEND / "app"

#: The three files this feature owns. Everything else in ``app/`` must be able to run without
#: knowing the scope exists — that is what "a stranger to every write gate" means in practice.
THE_FEATURE = {
    APP / "services" / "design_workshop_inspectors.py",
    APP / "api" / "routes" / "design_workshop_inspections.py",
    APP / "schemas" / "design_workshop_inspections.py",
}

#: The names that, spoken anywhere else, mean somebody has wired this scope into another path.
#:
#: The Prisma delegate is in the list and it is the one that matters most: a read of
#: ``db.designworkshopinspector`` outside this feature is a second place the scope is consulted from,
#: and the four places ``has_viewer_grant`` is consulted from are exactly the paths that would
#: acquire it first.
THE_NAMES = (
    "has_inspection_scope",
    "inspectable_by_clause",
    "load_inspectable_workshop_or_404",
    "designworkshopinspector",
)


def test_no_other_module_names_the_inspection_predicates():
    """**THE SWEEP.** The day somebody writes ``or await has_inspection_scope(...)`` beside a
    ``has_viewer_grant`` call, this is what goes red.

    That is not a hypothetical shape. ``has_viewer_grant`` is consulted from four modules and TWO
    MORE reads do not go through it at all — ``questionnaire_forms._visible_questionnaire_where``
    writes the relation filter by hand, and ``records._design_workshop_media_branches`` follows
    ``visible_to_clause`` on its own written instruction "so the day that widens again the audio
    widens with it". Following it with THIS clause hands an inspector the artisan's recorded voice.
    Six sites, and missing one is not a cosmetic bug — which is exactly the argument
    ``test_design_workshop_provisional_isolation`` makes for the mirror-image table.

    A registry of forbidden names is blunt and deliberately so: the drift being defended against is
    somebody reaching for an autocompleted symbol, and text is where that happens.

    IF THIS FAILS AND THE NEW CALL SITE IS DELIBERATE: it is not a matter of adding the file here.
    Adding a reader means the scope now decides something new, and the question to answer first is
    whether that something is a READ. If it is, say so in the module header and add the file to
    ``THE_FEATURE``. If it is a write, the answer is no.
    """
    offenders: list[str] = []
    for path in sorted(APP.rglob("*.py")):
        if path in THE_FEATURE:
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        lowered = text.lower()
        for name in THE_NAMES:
            if name in lowered:
                offenders.append(f"{path.relative_to(BACKEND)} names {name!r}")
    assert not offenders, (
        "the inspection scope has been wired into another path:\n  "
        + "\n  ".join(offenders)
        + "\nRead the header of app/services/design_workshop_inspectors.py before allowing it."
    )


def test_the_sweep_actually_reaches_the_modules_it_is_defending():
    """The backstop's backstop: a sweep that walks nothing passes for ever.

    Names the four files whose paths this scope is most likely to be wired into and asserts the
    sweep can actually see them — a typo in ``APP`` or a change to the tree layout would otherwise
    turn the test above into a permanent, silent pass.
    """
    seen = {p.relative_to(BACKEND).as_posix() for p in APP.rglob("*.py")}
    for expected in (
        "app/services/design_workshops.py",
        "app/services/design_workshop_viewers.py",
        "app/services/records.py",
        "app/api/routes/questionnaire_forms.py",
    ):
        assert expected in seen, f"the sweep never reached {expected}"
    assert set(APP.rglob("*.py")) >= THE_FEATURE, "THE_FEATURE names a file that does not exist"
