"""WHO MAY OPEN A WORKSHOP — both tables, both routes, and the sentence each one refuses with.

The ruling this file pins: **designers may not create workshops. Ministry and admin tiers create;
designers participate in them.** Enforced AT THE ROUTE with a 403, and the control dropped from both
clients — in that order of importance, because a hidden button over an open endpoint hides the link
and leaves the URL, the API and an APK a fortnight behind.

── WHAT WAS ALREADY TRUE BEFORE ANY OF THIS, SAID PLAINLY ───────────────────────────────────────
Half the rule was already enforced and this file would be dishonest if it read as though it were all
new. ``POST /design-workshops`` has been ``assert_can_create_design_workshops`` — ``{ADMIN,
MASTER_ADMIN}`` — since the create gate was split out, and its refusal has always been a sentence
naming the next move. ``POST /workshops`` was ``require_workshop_manager``, a rank floor at PROFESSOR
(40), which already refused a DESIGNER (35) — but refused them with "Adding or editing a workshop
requires Professor access or above", which names the wrong rule and the wrong next move, and which
let a professor, an assistant director and a regional director open workshops the ruling does not
give them.

So exactly one gate moved: ``POST /workshops`` is now :func:`require_workshop_opener`, a rank floor
at MINISTRY_ADMIN (48). ``PATCH /workshops/{id}`` did NOT move and
:func:`test_correcting_a_workshop_is_still_the_professor_floor` is the assertion that says so — this
change was allowed to take the CREATE away from a professor and was not allowed to take the EDIT.

── THE ONE PLACE THE TWO TABLES DISAGREE, AND IT IS DELIBERATE ──────────────────────────────────
"Ministry and admin tiers create" is true of ``Workshop`` and is NOT true of ``DesignWorkshop``,
where the creator set is the strictly narrower ``{ADMIN, MASTER_ADMIN}`` and a MINISTRY_ADMIN is
refused. That is not a gap left by this change; it is a standing decision recorded twice in the tree
— in ``services/sanction_orders.can_record_sanction_orders`` ("widening
``DESIGN_WORKSHOP_CREATOR_ROLES`` to the ministry tiers instead was considered and rejected") and at
``POST /annual-plan/{entry_id}/promote`` ("widening that set to fit would hand every ministry admin
the ordinary create button as well. Two doors, two gates, one creation path"). A ministry admin opens
a design & prototype workshop through the promote route, which has a published plan row behind it.
:func:`test_a_ministry_admin_is_refused_the_design_workshop_door_and_that_is_the_decision` states it
as an assertion so nobody discovers it as a bug.

── HOW THESE TESTS RUN, AND WHY THERE IS NO DATABASE ────────────────────────────────────────────
The harness is ``tests/test_design_workshop_access_gate.py``'s, borrowed for the same reason it was
written: the modules that need Postgres skip themselves on every CI machine, so a gate asserted only
there is a gate nobody checks before a merge. ``db`` is replaced by a tripwire that raises the moment
any delegate is read off it, and ``get_current_user`` is overridden with a bare role.

That gives the two outcomes this file needs, and "reached the database" is the stronger of the two:

* REFUSED — a real HTTP status and a real body, over the real routers, with the real dependency.
* ADMITTED — the handler got past every gate and went to write a row. "Not 403" would also be
  satisfied by a 422 from an unrelated cause, which is why success is spelled as a read attempt.

The tripwire is re-implemented here rather than imported from that module, exactly as that module
re-implemented it: a test file that imports another test file's harness makes two files fail together
for one cause and makes neither runnable alone.
"""

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

#: The floor the ruling put on ``POST /workshops``. MINISTRY_ADMIN, so ``{MINISTRY_ADMIN 48,
#: ADMIN 50, MASTER_ADMIN 60}`` — "ministry and admin tiers", in the ladder's own words.
WORKSHOP_CREATE_FLOOR = 48

#: Who may open a DESIGN & prototype workshop. A SET and not a floor, and strictly narrower than the
#: one above it: see this module's header for the two places that decision is argued.
DESIGN_WORKSHOP_CREATORS = ("ADMIN", "MASTER_ADMIN")

#: A body that VALIDATES, which matters only for the roles that get through. A gate raises during
#: dependency resolution, before the body is looked at, so a refused role needs nothing here — but an
#: ADMITTED role handed an invalid body is answered 422 and never reaches the tripwire, and the test
#: would then be asserting the schema rather than the gate. ``state`` and ``district`` are mandatory
#: on every create (``schemas/common.require_location``) and are normalised against a closed list,
#: which is why "Kutch" is stored as "Kachchh" and why neither is invented here.
WORKSHOP_BODY: dict[str, Any] = {
    "title": "Gate test workshop",
    "place": "Bhuj",
    "location": {
        "latitude": 23.25,
        "longitude": 69.67,
        "state": "Gujarat",
        "district": "Kutch",
    },
}

#: Only the title is required to start one, which is the schema's own first sentence.
DESIGN_WORKSHOP_BODY: dict[str, Any] = {"title": "Gate test design workshop"}


class _DatabaseTouched(Exception):
    """Raised by the tripwire. Escaping the request means the handler got past every gate."""


class _Tripwire:
    """Stands in for ``db``. Reading any delegate off it means a write was about to happen."""

    def __init__(self) -> None:
        object.__setattr__(self, "touched", False)

    def __getattr__(self, name: str) -> Any:
        # ``__getattr__`` and not ``__getattribute__``, so ``touched`` above stays readable.
        object.__setattr__(self, "touched", True)
        raise _DatabaseTouched(name)


class _Outcome:
    """Either "the handler went to write" or "the request was refused", never a bare status code."""

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


#: Assembled once. Every router with its response models costs a couple of seconds to build and
#: nothing request-scoped lives on it.
_APP = _build_app()


def _user(role: str) -> SimpleNamespace:
    """A user row as ``has_rank``, ``is_admin`` and ``role_value`` read one: a role and an id."""
    return SimpleNamespace(
        id=f"u-{role.lower()}", email=f"{role.lower()}@example.test", name="Gate Test", role=role
    )


@pytest.fixture
def api(monkeypatch: pytest.MonkeyPatch):
    """The real API with every module's ``db`` rebound to the tripwire.

    Rebinding BY IDENTITY rather than by module name: every module does ``from app.core.db import
    db``, so each holds its own reference and patching ``app.core.db`` alone would leave all of them
    pointing at the real client — and a test that quietly talked to Postgres would pass here and take
    eleven milliseconds a connection to do it.
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


def _create_a_workshop(api, role: str) -> _Outcome:
    return api.call("POST", "/workshops", as_role=role, body=WORKSHOP_BODY)


def _create_a_design_workshop(api, role: str) -> _Outcome:
    return api.call("POST", "/design-workshops", as_role=role, body=DESIGN_WORKSHOP_BODY)


# --------------------------------------------------------------------------------------
# POST /workshops — the `Workshop` table
# --------------------------------------------------------------------------------------


def test_a_designer_may_not_open_a_workshop(api):
    """THE RULE, on the route, in three digits.

    A designer typing the URL is refused by the server and not merely by a hidden button — which is
    the whole point of enforcing it here, because the handset and the offline outbox never see the
    browser's guard at all.
    """
    outcome = _create_a_workshop(api, "DESIGNER")
    assert outcome.status_code == 403, outcome


def test_the_refusal_a_designer_reads_is_a_sentence_and_names_the_next_move(api):
    """NOT A BARE 403, and the clauses are asserted individually because each one does a job.

    Somebody meeting this is standing in a courtyard with participants in front of them. "Forbidden"
    tells them to stop working. What is true is that the ministry opens the workshop, that it appears
    in their own list the moment they are added to it, and that every record they came out to capture
    still files against it — so all three are said, and a future edit that drops the second one turns
    a redirection into a dismissal.
    """
    detail = _create_a_workshop(api, "DESIGNER").detail
    assert detail == workshop_routes.WORKSHOP_CREATE_REFUSAL, detail
    assert "ministry" in detail.lower(), detail
    assert "Ministry Admin" in detail, detail
    assert "added to it" in detail, detail


@pytest.mark.parametrize(
    "role", sorted(r for r, rank in deps.ROLE_RANK.items() if rank < WORKSHOP_CREATE_FLOOR)
)
def test_every_tier_below_the_ministry_is_refused(api, role: str):
    """DERIVED FROM ``ROLE_RANK``, so a tier added later is covered without anybody remembering.

    A hand-written list of the roles below a floor goes stale the day the ladder grows, and the
    failure mode of a stale list here is a NEW TIER SILENTLY ADMITTED — a role nobody asserted about
    is a role nobody notices. This ladder has taken four inserts in three weeks.
    """
    outcome = _create_a_workshop(api, role)
    assert outcome.status_code == 403, outcome


@pytest.mark.parametrize("role", ["PROFESSOR", "ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR"])
def test_the_three_tiers_this_change_actually_took_it_from(api, role: str):
    """THE NARROWING, NAMED — and covered by the parametrize above, deliberately twice.

    A DESIGNER was already refused before this change (rank 35, under the old PROFESSOR floor of 40),
    so a file that only tested designers would pass against the OLD code and prove nothing. These
    three are the tiers whose behaviour genuinely changed, and they are the three a later "surely a
    professor can add a workshop" edit would put back. Read this test as the one that fails if the
    gate is reverted to ``require_workshop_manager``.

    They keep everything else. A professor still corrects any workshop in the repository — see
    :func:`test_correcting_a_workshop_is_still_the_professor_floor`.
    """
    outcome = _create_a_workshop(api, role)
    assert outcome.status_code == 403, outcome


def test_a_ministry_admin_opens_a_workshop(api):
    """The tier the ruling names first, and the reason the gate is a floor at 48 rather than
    ``is_admin``: ``is_admin`` is SET membership, ``{ADMIN, MASTER_ADMIN}``, so a MINISTRY_ADMIN at
    rank 48 is NOT an admin by it and would have been locked out of the act the ruling gives them.
    """
    outcome = _create_a_workshop(api, "MINISTRY_ADMIN")
    assert outcome.reached, outcome


@pytest.mark.parametrize("role", ["ADMIN", "MASTER_ADMIN"])
def test_an_admin_opens_a_workshop(api, role: str):
    """The platform tiers, unchanged by any of this and asserted so the narrowing cannot overshoot.

    The likeliest way to break this rule is not to leave it too wide, it is to tighten it to the
    ministry ALONE while "narrowing to the ministry tier" — which would take the workshop create away
    from the master admin, the one account that exists to fix everything else.
    """
    outcome = _create_a_workshop(api, role)
    assert outcome.reached, outcome


# --------------------------------------------------------------------------------------
# POST /design-workshops — the `DesignWorkshop` table
# --------------------------------------------------------------------------------------


def test_a_designer_may_not_open_a_design_workshop(api):
    """The same rule on the other table, and it was ALREADY enforced — this test is a regression
    guard rather than a new rule.

    It is here because the two routes must never drift: they are the two doors a designer meets when
    they try to start work, and a designer refused at one and admitted at the other would simply
    learn to use the other.
    """
    outcome = _create_a_design_workshop(api, "DESIGNER")
    assert outcome.status_code == 403, outcome


def test_the_design_workshop_refusal_is_a_sentence_too(api):
    """Same shape, different words, same two facts: who opens one, and what is open to them now."""
    detail = _create_a_design_workshop(api, "DESIGNER").detail
    assert detail == deps.DESIGN_WORKSHOP_CREATE_REFUSAL, detail
    assert "admin" in detail.lower(), detail
    assert "access" in detail.lower(), detail
    # "Any workshop you already have access to is open to you now" — the clause that stops this
    # reading as "your afternoon is cancelled".
    assert "open to you now" in detail, detail


@pytest.mark.parametrize("role", DESIGN_WORKSHOP_CREATORS)
def test_an_admin_opens_a_design_workshop(api, role: str):
    """The set that may, unchanged, asserted so the designer refusal above cannot be over-read as
    "nobody may"."""
    outcome = _create_a_design_workshop(api, role)
    assert outcome.reached, outcome


def test_a_ministry_admin_is_refused_the_design_workshop_door_and_that_is_the_decision(api):
    """**THE ONE PLACE THE TWO TABLES DISAGREE. NOT A BUG. DO NOT "FIX" IT HERE.**

    ``POST /workshops`` admits a MINISTRY_ADMIN and ``POST /design-workshops`` refuses one, and the
    obvious tidy-up — adding MINISTRY_ADMIN to ``DESIGN_WORKSHOP_CREATOR_ROLES`` so the two agree —
    has been considered and rejected twice in this tree:

    * ``services/sanction_orders.can_record_sanction_orders``: "``DESIGN_WORKSHOP_CREATOR_ROLES`` is
      ``{ADMIN, MASTER_ADMIN}`` and stays that way … widening it to the ministry tiers was considered
      and rejected: it would hand three new tiers the untracked create."
    * ``POST /annual-plan/{entry_id}/promote``: "a MINISTRY_ADMIN is not in
      ``DESIGN_WORKSHOP_CREATOR_ROLES``, and widening that set to fit would hand every ministry admin
      the ordinary create button as well. Two doors, two gates, one creation path."

    A ministry admin DOES open design & prototype workshops — through that promote route, with a
    published annual-plan row behind each one. What they do not get is the ad-hoc create with no
    instrument behind it. ``tests/test_directorate_tiers.py`` pins the set itself; this asserts the
    consequence at the door, so a reader who arrives from the ``Workshop`` table's rule and expects
    symmetry finds the answer as a test rather than as a surprise.
    """
    outcome = _create_a_design_workshop(api, "MINISTRY_ADMIN")
    assert outcome.status_code == 403, outcome


# --------------------------------------------------------------------------------------
# The gate is ON THE ROUTE, and the edit gate did not move with it
# --------------------------------------------------------------------------------------


def test_the_create_gate_is_wired_to_the_route(api):
    """Read off the route's SOURCE, which is how ``test_design_workshop_gate`` guards its own twin.

    Every assertion above would still pass if the gate were enforced somewhere deep inside a service
    helper — and a gate nobody can see at the door is a gate the next reader of the route does not
    know is there. So the dependency is asserted to be on the signature, by name.
    """
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
    """WHAT THIS CHANGE WAS NOT ALLOWED TO TAKE, and the assertion that proves it did not.

    A permission change that quietly cost a professor their edits would be far worse than the create
    rule is worth — the same argument ``can_create_design_workshops`` makes about designers and their
    stage writes. ``PATCH /workshops/{id}`` is untouched: ``require_workshop_manager``, Professor and
    above, plus the orthogonal workshop-access scope check inside the handler.
    """
    source = inspect.getsource(workshop_routes.update_workshop)
    assert "Depends(require_workshop_manager)" in source, (
        "the EDIT gate moved with the create gate; a professor can no longer correct a workshop"
    )


def test_the_two_gates_are_different_functions_and_that_is_the_point(api):
    """One name over two acts is what made the old rule wrong; two names is the fix.

    If somebody ever collapses these back into one dependency, both of the tests above can still pass
    while the rule is silently one thing again.
    """
    assert workshop_routes.require_workshop_opener is not deps.require_workshop_manager


# --------------------------------------------------------------------------------------
# One sentence, three surfaces
# --------------------------------------------------------------------------------------


def _kotlin_string_after(text: str, marker: str) -> str:
    """The concatenated Kotlin string literal declared immediately after *marker*.

    Kotlin has no implicit adjacent-literal concatenation, so a long constant is written as a chain
    of ``"…" +`` lines. The block ends at the first blank line, which is what bounds the search.
    """
    after = text.split(marker, 1)[1]
    block = after.split("\n\n", 1)[0]
    return "".join(re.findall(r'"((?:[^"\\]|\\.)*)"', block))


def test_the_handset_says_exactly_what_the_server_says():
    """BYTE FOR BYTE, because three surfaces say this and a refusal that names a different next move
    depending on where you met it is not a rule, it is three rumours.

    The handset is the surface where this matters most: a designer who only met the rule at sync time
    would already have filled in a workshop, attached photographs and queued it to the outbox.
    """
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
    """The card and the form slot both read ``canCreate(EntryMode.WORKSHOP)``, and the predicate
    under it is the ministry floor rather than the Professor floor it used to be.

    Asserted as text because the alternative is an instrumented Compose test for a permission rule,
    and the thing that actually goes wrong here is somebody restoring one identifier.
    """
    text = (REPO / "android/app/src/main/java/com/designprototype/workshop/MainActivity.kt").read_text(
        encoding="utf-8"
    )
    assert "EntryMode.WORKSHOP -> user.canOpenAWorkshop()" in text
    assert "roleRank(role) >= RANK_MINISTRY_ADMIN" in text
    assert "canManageTheWorkshops" not in text, (
        "the old Professor-floor workshop predicate is back; a duplicate with no callers is how this "
        "defect returns"
    )
    # The form slot itself refuses, because the drawer row is still open at Professor and above.
    assert "EntryMode.WORKSHOP -> if (canCreate(EntryMode.WORKSHOP)) {" in text


def test_the_web_list_page_drops_the_create_form_and_names_the_ministry():
    """The web's half: the form is drawn for somebody who may CREATE, or for an edit already loaded
    into it, and what stands in its place names a tier and a next move.

    THE SENTENCE IT REPLACED WAS ACTIVELY WRONG, not merely stale: "Ask the master admin for workshop
    creation access" sent people to ask for a grant that does not exist — the server reads rank alone
    and there is no column anybody can set. It is asserted absent so it cannot come back with a copy
    of this page.
    """
    text = (REPO / "frontend/app/(protected)/workshops/page.tsx").read_text(encoding="utf-8")

    # THE PREDICATE, NOT THE THRESHOLD, AND THE THRESHOLD WHERE IT LIVES — which is a change of shape
    # rather than a weakening. This asserted `const allowCreate = hasRank(user, "MINISTRY_ADMIN");`
    # while the page spelled the floor inline, because the create affordance and `lib/permissions.ts`
    # were in different slices and the page's own comment named the named twin as the follow-up. The
    # twin landed, so the page now reads `canCreateWorkshops(user)` and the rank floor is asserted in
    # the one file that holds it. Pinning the old literal here would have made the follow-up
    # impossible to land without editing this test — which is the right amount of friction for a
    # THRESHOLD and the wrong amount for the name of the function carrying it.
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
    """The other client half, which was ALREADY right and is asserted so it stays right.

    ``/design-workshops`` has gated its create control on ``canCreateDesignWorkshops`` and shown
    ``DESIGN_WORKSHOP_CREATE_REFUSAL`` in words since the design-workshop rule landed. Nothing in
    this change touched that page; this test exists so that "nothing touched it" keeps being true.
    """
    text = (REPO / "frontend/app/(protected)/design-workshops/page.tsx").read_text(encoding="utf-8")
    assert "const allowCreate = canCreateDesignWorkshops(user);" in text
    assert "DESIGN_WORKSHOP_CREATE_REFUSAL" in text
