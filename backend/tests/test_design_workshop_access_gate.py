"""Which door each caller gets, and WHEN the database is first touched. No database required.

TWO QUESTIONS THIS ANSWERS THAT ``test_design_workshop_access_requests`` CANNOT, and the second is
the important one.

**ONE: THE ROLE GATES, IN CI.** That module needs Postgres and skips itself wherever there is not a
local one — which is every CI run, deliberately, because the deployed database is not a scratch pad.
So the assertions that a designer may ASK and may not LIST or DECIDE would never be made on the one
machine that gates a merge. They are made here instead, over the real routers, with ``db`` replaced.

**TWO: THE ORDER OF THE REFUSALS, WHICH IS THE ENUMERATION ARGUMENT ITSELF.**
``services/design_workshop_access`` claims, in its header and in ``file_request``'s docstring, that
the only thing the ask route ever says out loud is a refusal about the REQUEST BODY — the scanned
code — and that it is said BEFORE any database read, every branch after it being silent. That claim
is the whole reason a 422 there is not an existence oracle, and it is exactly the kind of claim that
a later edit breaks by moving one line. A test over a real database cannot see it: the 422 looks
identical whether it was decided before or after a lookup.

Here it is visible. ``db`` is a tripwire that raises the moment any delegate is read off it, so
"HTTP 422 and the tripwire was never touched" means the refusal was decided from the body alone —
which is the property, stated as an assertion rather than as prose. If somebody moves the workshop
lookup above the code check, the status code does not change and this test goes red.

THE TRIPWIRE IS THE ONE FROM ``test_permission_matrix``, deliberately re-implemented in twenty lines
rather than imported from it. A test module that imports another test module's harness makes two
files fail together for one cause and makes neither runnable on its own; and that module's ``_Api``
carries a body table, a Verhoeff generator and a role ladder, none of which this file needs. What is
borrowed is the IDEA and the rebinding trick, which is the part that is easy to get wrong: every
module does ``from app.core.db import db``, so each holds its own reference and patching the source
alone would miss all of them.
"""

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

#: A cuid-shaped id that names nothing. Nothing here reaches a database, so it never needs to.
WORKSHOP_ID = "cmgatetest0000000000000aa"


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

    The distinction is the whole point of the file: a 422 that reached the database and a 422 that
    did not are the same three digits and two different security properties.
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
    """A user row as ``require_admin`` and ``is_admin`` read one: the role, and an id."""
    return SimpleNamespace(
        id=f"u-{role.lower()}", email=f"{role.lower()}@example.test", name="Gate Test", role=role
    )


@pytest.fixture
def api(monkeypatch: pytest.MonkeyPatch):
    """The real API with every module's ``db`` rebound to the tripwire.

    Rebinding BY IDENTITY rather than by module name: the modules do ``from app.core.db import db``,
    so each holds its own reference, and patching only ``app.core.db`` would leave every one of them
    pointing at the real client. Finding them by identity also keeps working when a module is added.
    """
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


# --------------------------------------------------------------------------------------
# Who gets which door
# --------------------------------------------------------------------------------------


def test_a_designer_may_ask(api):
    """The ask is open to a designer, and reaching the database is what proves it.

    "Not 403" would also be satisfied by a 422 from an unrelated cause, which is why the outcome
    here is "a read was attempted" rather than a status code.
    """
    outcome = api.call(
        "POST",
        "/design-workshop-access/requests",
        as_role="DESIGNER",
        body={"workshopId": WORKSHOP_ID, "scannedCode": _code(WORKSHOP_ID)},
    )
    assert outcome.reached, outcome


def test_a_researcher_may_ask_too_because_the_role_is_checked_at_the_grant(api):
    """Not a gap. The rule about who may hold a viewer row lives in ``replace_viewers``, which reads
    both rosters and answers with a sentence naming the screen that fixes it. Refusing quietly at
    this door instead would leave an admin unable to see that somebody had asked at all — and the
    grant would still have to check, so the rule would exist twice.
    """
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
    """403 for everybody below admin, and the database never touched.

    THE QUEUE IS A DIRECTORY OF WHO IS TRYING TO GET WHERE — every account that has asked about
    anything, across every workshop — so nothing about being allowed to ASK implies being allowed to
    read it. PROFESSOR is in this list on purpose: they outrank a designer on the ladder and are
    still not an administrator, which is the row a rank comparison written in place of
    ``require_admin`` would get wrong.
    """
    outcome = api.call(method, path, as_role=role, body=body)
    assert outcome.status_code == 403, outcome
    assert api.tripwire.touched is False, "the refusal must fire before any database read"


@pytest.mark.parametrize("role", ["ADMIN", "MASTER_ADMIN"])
def test_an_admin_reaches_the_queue(api, role):
    outcome = api.call("GET", "/design-workshop-access/requests", as_role=role)
    assert outcome.reached, outcome


# --------------------------------------------------------------------------------------
# The refusals that must be decided from the body alone
# --------------------------------------------------------------------------------------


@pytest.mark.parametrize(
    "code",
    [
        # A check character one out. The refusal the whole grammar exists for.
        "DPW1:G:CMGATETEST0000000000000AA:AAAA",
        # Ours and well formed, but it names an artisan rather than a workshop.
        "DPW1:A:CMGATETEST0000000000000AA:NEWD",
        # Not one of ours at all.
        "https://example.org/scan-me",
        # A workshop that exists only on the handset that made it. Both clients' spellings.
        "DPW1:G:LOCAL-3F2504E0-4F89-11D3-9A0C-0305E82C3301:AAAA",
        "DPW1:G:DWLOCAL-3F2504E0-4F89-11D3-9A0C-0305E82C3301:AAAA",
    ],
)
def test_a_bad_code_is_refused_before_the_database_is_asked_anything(api, code):
    """THE ENUMERATION ORDERING, AS AN ASSERTION RATHER THAN A PARAGRAPH.

    The ask route answers the same uniform 202 for a real workshop, a soft-deleted one and an id
    that names nothing — that is what stops it being an existence oracle, and it is asserted over a
    real database in ``test_design_workshop_access_requests``. The one thing it DOES say out loud is
    that the scanned code is unreadable, and that is only safe while the refusal is decided from the
    body alone: a code check that ran AFTER a lookup could refuse differently depending on whether
    the id existed, and the status code would look exactly the same.

    So the assertion is not merely 422. It is 422 WITH THE DATABASE NEVER TOUCHED. Move the workshop
    lookup above the code check and this is the test that notices.
    """
    outcome = api.call(
        "POST",
        "/design-workshop-access/requests",
        as_role="DESIGNER",
        body={"workshopId": WORKSHOP_ID, "scannedCode": code},
    )
    assert outcome.status_code == 422, outcome
    assert api.tripwire.touched is False, "the code refusal must not depend on a database read"


def test_a_code_for_another_workshop_is_refused_before_the_database_is_asked_anything(api):
    """The mismatch case, which is the one a client bug produces rather than a damaged card.

    A perfectly good code, posted alongside a different workshop's id. Refused, and refused from the
    body: the two ids disagree whether or not either of them names anything real, so nothing about
    the database is disclosed by saying so.
    """
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
    """A statement about the request, so it is named out loud — and it costs no query to say it."""
    outcome = api.call(
        "GET", "/design-workshop-access/requests?statusFilter=SOMEDAY", as_role="ADMIN"
    )
    assert outcome.status_code == 422, outcome
    assert "PENDING" in outcome.detail, outcome
    assert api.tripwire.touched is False


def test_an_unknown_decision_is_refused_before_the_row_is_read(api):
    """PENDING is a state a row STARTS in, not a decision anybody makes, so it is not accepted.

    Checked before the row is loaded, which is why this is worth an assertion: validating the token
    after ``require_record`` would answer 404 for an unknown id and 422 for a known one, and a
    caller could then use a deliberately invalid decision to test whether a request id exists.
    """
    for token in ("PENDING", "MAYBE", ""):
        outcome = api.call(
            "POST",
            "/design-workshop-access/requests/anything/decide",
            as_role="ADMIN",
            body={"status": token} if token else {"status": " "},
        )
        assert outcome.status_code == 422, (token, outcome)
        assert api.tripwire.touched is False, token
