"""The visibility rule, asserted with NO DATABASE — the half of the scope that runs in CI.

WHY THIS FILE EXISTS BESIDE ``test_design_workshop_designer_scope``
==================================================================

That module is the end-to-end proof: real accounts, real workshops, real 404s. It needs Postgres
and **skips itself wherever there is not a local one — which is every CI run, deliberately**, because
the deployed database is not a scratch pad and the job that gates a merge deliberately has no
database in it (see ``.github/workflows/checks.yml``, which says so at length). So the assertions
that a designer cannot see somebody else's fieldwork would never be made on the one machine that
gates a merge.

They are made here instead, over the REAL route function and the REAL loader, with ``db`` replaced
by a stub that records the query it was handed. This is the same shape, and the same argument, as
``test_dw_inspector_scope_gate`` beside ``test_dw_inspector_scope``: a guard that is skipped exactly
when nobody is watching is not a guard.

WHAT IS PINNED, AND HOW EACH ONE FAILS IN PRODUCTION
====================================================

1. **THE SCOPE IS IN THE QUERY, NOT IN THE ANSWER.** The ``where`` the list route hands Postgres is
   captured and read. A route that fetched everything and filtered the list afterwards would pass any
   test that only counted the rows coming back, and would still be a leak the moment anybody added a
   ``total``, a facet count, or a second page — and the single read is one typed URL away regardless.

2. **IT IS AND-COMPOSED, NEVER WRITTEN TO ``where["OR"]``.** The search box already owns that key.
   Two assignments to it and the later one silently wins: either the search stops narrowing, or THE
   SCOPE STOPS APPLYING and every workshop matching the term comes back. Neither is visible to a
   reader — the code looks like it filters, and it does, on the wrong axis.

3. **ADMIN AND MASTER_ADMIN GET NO CLAUSE AT ALL**, which is the owner's "admins and master admins
   would be able to see all the design workshops" stated as a property of the SQL rather than of a
   screen.

4. **THE SINGLE READ REFUSES, AND ITS REFUSAL IS BYTE-IDENTICAL TO A ROW THAT DOES NOT EXIST.** Not
   403. A 403 confirms the id exists to exactly the people the clause is turning away, and design
   workshops are keyed by cuid with their ids printed on JOIN CARDS.

5. **THE CLAUSE IS THE ONE FROM ``design_workshop_viewers``, IMPORTED AND NOT RESTATED.** The whole
   design of the multi-select rests on there being exactly ONE table that decides access; a second
   spelling of the predicate is the first half of a second source of truth.

Nothing here reads a database, an enum or an environment variable, so it is correct on a laptop with
no Docker and on a runner with no Postgres service.
"""

from __future__ import annotations

import inspect
from types import SimpleNamespace
from typing import Any

import pytest
from fastapi import HTTPException

from app.api.routes import design_workshops as routes
from app.services import design_workshop_viewers as viewers, design_workshops as service

pytestmark = pytest.mark.anyio


@pytest.fixture
def anyio_backend():
    return "asyncio"


#: The four callers whose treatment differs. Roles as plain strings, because ``role_value`` unwraps
#: an enum or takes a string, and a string keeps this module independent of the generated client.
LEAD = SimpleNamespace(id="designer-1", role="DESIGNER")
CO = SimpleNamespace(id="designer-2", role="DESIGNER")
STRANGER = SimpleNamespace(id="designer-9", role="DESIGNER")
ADMIN = SimpleNamespace(id="admin-1", role="ADMIN")
MASTER = SimpleNamespace(id="master-1", role="MASTER_ADMIN")

#: An INSPECTOR is deliberately here too. The tier outranks a designer for review and is
#: deliberately OUTSIDE ``deps.DESIGN_WORKSHOP_ROLES``, so every "this tier and above" spelling of
#: the visibility rule would hand them the whole list. ``is_admin`` is a SET and does not.
INSPECTOR = SimpleNamespace(id="inspector-1", role="INSPECTOR")


class _ListTripwire:
    """``db.designworkshop`` with only what the LIST route may touch, recording the query.

    Deliberately not a mock library and not a fake that accepts any method: a stub this small fails
    loudly if the route starts doing something else — a second query, a read of another table —
    instead of absorbing it silently.
    """

    def __init__(self) -> None:
        self.wheres: list[dict[str, Any]] = []

    async def count(self, where: dict[str, Any]) -> int:
        self.wheres.append(where)
        return 0

    async def find_many(self, **kwargs: Any) -> list[Any]:
        self.wheres.append(kwargs.get("where"))
        return []


async def _where_for(monkeypatch: Any, user: Any, **params: Any) -> dict[str, Any]:
    """Run the REAL list route against the tripwire and return the ``where`` it built.

    BOTH the ``count`` and the ``find_many`` are asserted to have been given the SAME filter. Today
    that is trivially true — the route builds one dict and passes the same object to both statements
    of its ``asyncio.gather`` — and the assertion is here for the day it is not: a scope applied to
    only one of them yields a page of the caller's own workshops under a total counted over
    everybody's, which is a number telling a designer how many workshops exist in the institution.
    A count and a page that disagree is the cheapest kind of leak to ship and the hardest to notice,
    because every row on screen is one the caller may see.

    ``page`` AND ``pageSize`` ARE PASSED EXPLICITLY, and that is a requirement of calling a route
    function directly rather than through the app. Their defaults in the signature are FastAPI
    ``Query(...)`` objects, which FastAPI replaces with real values when it resolves a request and
    which arrive here untouched — so omitting them hands ``normalize_pagination`` a ``Query`` and
    the test dies inside the pagination helper with a ``TypeError``, several frames away from
    anything it is about.
    """
    table = _ListTripwire()
    monkeypatch.setattr(routes, "db", SimpleNamespace(designworkshop=table))
    await routes.list_design_workshops(current_user=user, page=1, pageSize=20, **params)
    assert len(table.wheres) == 2, (
        f"the list route issued {len(table.wheres)} queries; the count and the page must be the "
        "only two, and both must carry the scope"
    )
    assert table.wheres[0] == table.wheres[1], (
        "the count and the page were filtered differently, so the total counts workshops the page "
        "may not show"
    )
    return table.wheres[0]


def _visibility_clauses(where: dict[str, Any]) -> list[Any]:
    """Every AND-composed term that looks like the visibility scope."""
    return [term for term in where.get("AND", []) if "OR" in term]


# ══════════════════════════════════════════════════════════════════════════════════════════════
# 1. The list: the scope is in the WHERE, or it is nowhere
# ══════════════════════════════════════════════════════════════════════════════════════════════


async def test_a_designer_gets_the_visibility_clause_in_the_query(monkeypatch):
    """And it is the clause from ``design_workshop_viewers``, not a second spelling of it.

    Compared against ``visible_to_clause(user.id)`` itself rather than against a literal, because
    the property is that there is ONE predicate deciding access. A hand-written copy here would
    pass a literal comparison and would then be a second place to look when somebody has access
    they should not — the failure ``DesignWorkshopViewer``'s schema comment forbids by name.
    """
    where = await _where_for(monkeypatch, LEAD)
    assert _visibility_clauses(where) == [viewers.visible_to_clause(LEAD.id)]


@pytest.mark.parametrize("user", [ADMIN, MASTER], ids=["admin", "master_admin"])
async def test_an_admin_and_the_master_admin_get_no_clause_at_all(monkeypatch, user):
    """"Admins and master admins would be able to see all the design workshops", as a property of the SQL."""
    where = await _where_for(monkeypatch, user)
    assert _visibility_clauses(where) == []


async def test_a_tier_that_merely_OUTRANKS_a_designer_is_still_scoped(monkeypatch):
    """The rule is ``is_admin``, a SET, and not a rank floor — asserted with the account that tells them apart.

    INSPECTOR sits at rank 37, above DESIGNER's 35. Every "this tier and above" spelling of "who
    sees everything" hands them the whole institution's fieldwork; the set does not. A test with
    only a designer in it passes just as well against the wrong rule.
    """
    where = await _where_for(monkeypatch, INSPECTOR)
    assert _visibility_clauses(where) == [viewers.visible_to_clause(INSPECTOR.id)]


async def test_the_scope_and_the_search_box_do_not_collide(monkeypatch):
    """Both want ``where["OR"]``. Written naively the later assignment silently wins.

    THE DANGEROUS DIRECTION IS THE ONE ASSERTED FIRST: if the SCOPE is the loser, every workshop in
    the institution matching the typed term comes back to a designer who is on none of them. The
    other direction — the search being the loser — is a search that stops narrowing, which is a
    nuisance rather than a leak, and is asserted too because a fix that traded one for the other
    would otherwise look correct.
    """
    where = await _where_for(monkeypatch, LEAD, search="Kalamkari")

    assert _visibility_clauses(where) == [viewers.visible_to_clause(LEAD.id)], (
        "typing in the search box removed the visibility scope, so the list now answers with every "
        "workshop matching the term regardless of who may see it"
    )
    titles = [term for term in where.get("OR", []) if "title" in term]
    assert titles, "the visibility scope overwrote the search filter, so the search stopped narrowing"


async def test_mineOnly_narrows_to_the_callers_OWN_and_does_not_fall_back_to_everything(monkeypatch):
    """The one branch that does not compose the scope, pinned so it cannot be read as optional.

    ``mineOnly`` means OWN, deliberately excluding the granted ones, so it REPLACES the clause
    rather than narrowing under it. That is correct — and it is also the single place a later
    reader could mistake for "the scope is optional here", so what it actually produces is written
    down: an equality on ``createdById`` and no unscoped fallback.
    """
    where = await _where_for(monkeypatch, LEAD, mineOnly=True)
    assert where["createdById"] == LEAD.id
    assert _visibility_clauses(where) == []


async def test_the_trash_is_refused_to_a_designer_before_any_query_runs(monkeypatch):
    """``includeDeleted``/``deletedOnly`` are admin-only, and refused rather than ignored.

    A designer who asked for the trash and silently got the live list would render an ordinary
    workshop under a "Deleted" heading with a Restore button beside it. Asserted BEFORE the query,
    because a refusal that happens after the read has already read.
    """
    table = _ListTripwire()
    monkeypatch.setattr(routes, "db", SimpleNamespace(designworkshop=table))
    for flag in ("includeDeleted", "deletedOnly"):
        with pytest.raises(HTTPException) as raised:
            await routes.list_design_workshops(
                current_user=LEAD, page=1, pageSize=20, **{flag: True}
            )
        assert raised.value.status_code == 403
    assert table.wheres == [], "the trash flags were refused only after the rows had been read"


# ══════════════════════════════════════════════════════════════════════════════════════════════
# 2. The single read: the other door, and a refusal that discloses nothing
# ══════════════════════════════════════════════════════════════════════════════════════════════


def _record(**overrides: Any) -> Any:
    """One workshop row, created by an admin and deleted by nobody unless a test says otherwise."""
    return SimpleNamespace(**{"id": "ws-1", "createdById": ADMIN.id, "deletedAt": None, **overrides})


def _load_against(monkeypatch: Any, *, record: Any, grantees: set[str]) -> None:
    """Point ``load_workshop_or_404`` at one in-memory row and one in-memory grant set."""
    async def _find_unique(where: dict[str, Any]) -> Any:
        return record if record is not None and where["id"] == record.id else None

    async def _has_grant(workshop_id: str, user_id: str) -> bool:
        return user_id in grantees

    monkeypatch.setattr(
        service, "db", SimpleNamespace(designworkshop=SimpleNamespace(find_unique=_find_unique))
    )
    monkeypatch.setattr(service, "has_viewer_grant", _has_grant)


async def test_every_named_designer_can_open_it_and_the_unnamed_one_cannot(monkeypatch):
    """The whole of the multi-select's effect on the read, in one assertion.

    The workshop was created by an admin and named two designers, so the two hold viewer rows and a
    third designer holds nothing. ``createdById`` matches none of the three — a DESIGNER cannot
    create a workshop at all — so the grant is the ONLY thing admitting the first two.
    """
    _load_against(monkeypatch, record=_record(), grantees={LEAD.id, CO.id})

    for user in (LEAD, CO):
        assert (await service.load_workshop_or_404("ws-1", user)).id == "ws-1"

    with pytest.raises(HTTPException) as raised:
        await service.load_workshop_or_404("ws-1", STRANGER)
    assert raised.value.status_code == 404


@pytest.mark.parametrize("user", [ADMIN, MASTER], ids=["admin", "master_admin"])
async def test_an_admin_needs_neither_a_grant_nor_to_have_created_it(monkeypatch, user):
    """Property 3 on the read door. The row is created by somebody else and granted to nobody."""
    _load_against(monkeypatch, record=_record(createdById="somebody-else"), grantees=set())
    assert (await service.load_workshop_or_404("ws-1", user)).id == "ws-1"


async def test_the_refusal_cannot_be_told_apart_from_a_workshop_that_does_not_exist(monkeypatch):
    """404 and not 403, with the SAME detail string. The reason it is not 403.

    Design workshops are keyed by cuid and their ids travel on printed join cards, so a
    distinguishable refusal is a free existence oracle over a research data set: hold a card, learn
    whether the workshop is real, and learn it from the server that just turned you away.

    Asserted as an EQUALITY between the two refusals rather than against a literal, because the
    property is that they cannot be told apart — checking each against a string would still pass on
    the day one of them gained a header, a code or an extra sentence.
    """
    _load_against(monkeypatch, record=_record(), grantees={LEAD.id})

    with pytest.raises(HTTPException) as turned_away:
        await service.load_workshop_or_404("ws-1", STRANGER)
    with pytest.raises(HTTPException) as never_existed:
        await service.load_workshop_or_404("no-such-workshop", STRANGER)

    assert (turned_away.value.status_code, turned_away.value.detail) == (
        never_existed.value.status_code,
        never_existed.value.detail,
    )
    assert turned_away.value.detail == "Record not found"


async def test_a_designer_removed_from_the_workshop_is_refused_again_immediately(monkeypatch):
    """No cache, no second source. Taking somebody off the list is what takes away their access.

    The grant is read on every load that gets past the two cheap comparisons, so a designer an
    admin has just removed from the picker is refused by the very next request. This is the property
    that makes the viewers screen a control rather than a label — and it is the property a second
    access table would quietly break, because the row removed on one screen would not be the row the
    loader consults.
    """
    grantees = {LEAD.id}
    _load_against(monkeypatch, record=_record(), grantees=grantees)
    assert (await service.load_workshop_or_404("ws-1", LEAD)).id == "ws-1"

    grantees.discard(LEAD.id)
    with pytest.raises(HTTPException) as raised:
        await service.load_workshop_or_404("ws-1", LEAD)
    assert raised.value.status_code == 404
    assert raised.value.detail == "Record not found"


# ══════════════════════════════════════════════════════════════════════════════════════════════
# 3. One table decides access, and the create is what writes into it
# ══════════════════════════════════════════════════════════════════════════════════════════════


def test_the_create_route_grants_through_the_viewer_table_and_invents_no_second_one():
    """A source sweep over the create route.

    The multi-select's whole design is that "visible only to those particular designers" is the
    EXISTING ``DesignWorkshopViewer`` rule, and that the new field merely writes N rows into it
    where the old one wrote one. The day somebody adds a ``DesignWorkshopDesigner`` table beside it
    is the day there are two places to look when a person has access they should not — and the
    hand-written ``viewers: {some: …}`` in ``questionnaire_forms`` is the arm that would be missed,
    surfacing as a co-designer who can open the workshop and finds its questionnaires empty.

    Asserted on the route rather than on the whole file, so an unrelated feature adding a table of
    its own does not fail this.
    """
    source = inspect.getsource(routes.create_design_workshop)
    assert "attach_the_named_designers(" in source
    assert "designworkshopdesigner" not in source.lower(), (
        "the create route reached for a second table to record who a workshop is for; access is "
        "decided by DesignWorkshopViewer and by nothing else"
    )


def test_the_list_and_the_read_consult_the_same_one_table():
    """The two doors must agree, and they agree by sharing a predicate rather than by matching.

    ``visible_to_clause`` (the list) and ``has_viewer_grant`` (the read) are the two readers of
    ``DesignWorkshopViewer`` on this path, and both live in ``design_workshop_viewers``. If either
    door ever grows its own spelling, a workshop becomes listable-but-unreadable or the reverse —
    and the first of those is the state where a designer is told a workshop both exists and does
    not, with no screen in either client able to show it to them.
    """
    assert "visible_to_clause" in inspect.getsource(routes.list_design_workshops)
    assert "has_viewer_grant" in inspect.getsource(service.load_workshop_or_404)
    assert viewers.visible_to_clause("u") == {
        "OR": [{"createdById": "u"}, {"viewers": {"some": {"userId": "u"}}}]
    }
