"""A ``DesignWorkshopViewer`` row is only honoured while its holder can still run a workshop.

WHAT THIS DEFENDS
==================

``design_workshop_viewers`` REFUSES, by name and with the role in the sentence, to write a viewer
row for an account outside ``deps.DESIGN_WORKSHOP_ROLES``. Until 2026-09-03 the READ path did not
ask: ``design_workshops.load_workshop_or_404`` honoured any row in that table whatever the account
had become since. The rule therefore held at grant time and lapsed the moment somebody's role
changed — and a viewer row is a permanent object with no expiry and no status column, deliberately,
so nothing anywhere would have noticed.

**WHAT THAT BUYS IS NOT MERELY READ.** ``design_workshop_inspectors``' own module header counts it:
fourteen write routes pair this loader with ``_require_designer``, and ``for_edit=True`` performs no
role check of its own. A designer demoted to RESEARCHER, moved to PROFESSOR, or promoted to
INSPECTOR kept every workshop an old grant named, with the stage writes attached.

The tests here are the three sides of that:

* a grantee whose role has moved outside the set gets the SAME 404 a stranger gets — same status,
  same detail string, and no ``has_viewer_grant`` query at all, because the role test comes first
  and short-circuits;
* a grantee who is still a DESIGNER is admitted exactly as before, so the fix is not a lockout;
* an INSPECTOR's scope is untouched, because it never came through this door. That is asserted from
  both ends: this loader refuses them (it always did), and ``load_inspectable_workshop_or_404`` —
  the one they actually use — still hands the workshop back.

No database. Both loaders are called directly against a fake ``db.designworkshop`` and a stubbed
grant lookup, the same way ``test_workshops.py`` exercises a route against a fake table. What is
under test is a policy decision, and a policy is exactly the thing that should be provable without
Postgres.
"""

from __future__ import annotations

from types import SimpleNamespace
from typing import Any

import pytest
from fastapi import HTTPException

from app.core.deps import DESIGN_WORKSHOP_ROLES
from app.services import design_workshop_inspectors as inspectors, design_workshops as service

#: The workshop every test asks for. ``createdById`` is deliberately somebody else: the creator arm
#: of the loader short-circuits before the grant clause, so a test whose caller created the row
#: would prove nothing about grants at all.
WORKSHOP_ID = "dw-1"
OWNER_ID = "u-owner"


def _workshop(deleted: bool = False) -> Any:
    return SimpleNamespace(
        id=WORKSHOP_ID,
        createdById=OWNER_ID,
        deletedAt=SimpleNamespace() if deleted else None,
    )


def _user(role: str, uid: str = "u-grantee") -> Any:
    return SimpleNamespace(id=uid, role=role)


class _WorkshopTable:
    """``db.designworkshop`` — one row, and a record of whether it was asked for."""

    def __init__(self, row: Any) -> None:
        self.row = row
        self.finds: list[dict[str, Any]] = []

    async def find_unique(self, **kwargs: Any) -> Any:
        self.finds.append(kwargs)
        return self.row


def _install(monkeypatch: Any, *, row: Any, grant: bool) -> list[tuple[str, str]]:
    """Point the loader at a fake table and a stubbed grant lookup.

    Returns the list the grant lookup appends to, so a test can assert the query was NOT made —
    which is half of what makes the role test worth having: it is cheaper than the round trip it
    replaces, not an extra one bolted in front.
    """
    asked: list[tuple[str, str]] = []

    async def _has_viewer_grant(workshop_id: str, user_id: str) -> bool:
        asked.append((workshop_id, user_id))
        return grant

    monkeypatch.setattr(service, "db", SimpleNamespace(designworkshop=_WorkshopTable(row)))
    monkeypatch.setattr(service, "has_viewer_grant", _has_viewer_grant)
    return asked


# --------------------------------------------------------------------------------------
# The demotion, which is the whole finding
# --------------------------------------------------------------------------------------


@pytest.mark.parametrize("role", ["RESEARCHER", "PROFESSOR", "FIELD_CONTRIBUTOR", "INSPECTOR"])
async def test_a_grant_is_not_honoured_once_the_account_leaves_the_workshop_roles(
    monkeypatch, role
):
    """The row is still in the table and is no longer a way in.

    Parameterised over four roles rather than one because the set is a SET and not a rank floor:
    PROFESSOR outranks DESIGNER and still cannot run a workshop, so a fix written as "this tier and
    above" would pass a one-role test and quietly admit the senior half of the ladder.
    """
    asked = _install(monkeypatch, row=_workshop(), grant=True)
    with pytest.raises(HTTPException) as refusal:
        await service.load_workshop_or_404(WORKSHOP_ID, _user(role))
    assert refusal.value.status_code == 404
    assert refusal.value.detail == "Record not found", (
        "the refusal must be indistinguishable from a stranger's — a different status or a "
        "different sentence confirms to a demoted account that the id exists"
    )
    assert asked == [], (
        "the grant table was queried for an account whose role can never be admitted; the role "
        "test has to come first, or the fix costs a round trip instead of saving one"
    )


async def test_the_same_account_is_admitted_again_the_moment_the_role_comes_back(monkeypatch):
    """The counterpart, and the reason this is a gate rather than a revocation.

    Nothing is deleted and nothing is written: the row goes on meaning what it meant, and the
    account starts passing again as soon as its role does. An admin restoring somebody's DESIGNER
    role does not also have to remember to re-grant every workshop they were on.
    """
    asked = _install(monkeypatch, row=_workshop(), grant=True)
    record = await service.load_workshop_or_404(WORKSHOP_ID, _user("DESIGNER"))
    assert record.id == WORKSHOP_ID
    assert asked == [(WORKSHOP_ID, "u-grantee")]


async def test_a_designer_grantee_still_reaches_the_edit_door(monkeypatch):
    """``for_edit=True`` is the fourteen write routes, and the fix must not have closed it."""
    _install(monkeypatch, row=_workshop(), grant=True)
    record = await service.load_workshop_or_404(WORKSHOP_ID, _user("DESIGNER"), for_edit=True)
    assert record.id == WORKSHOP_ID


async def test_a_designer_without_a_grant_is_still_refused(monkeypatch):
    """The clause narrowed WHO a grant admits; it did not invent a grant for anybody."""
    _install(monkeypatch, row=_workshop(), grant=False)
    with pytest.raises(HTTPException) as refusal:
        await service.load_workshop_or_404(WORKSHOP_ID, _user("DESIGNER"))
    assert refusal.value.status_code == 404


async def test_the_creator_and_the_admin_arms_are_untouched(monkeypatch):
    """Neither arm was gated, deliberately, and each is asserted so a later tidy-up cannot merge them.

    A demoted CREATOR losing sight of the fortnight of fieldwork they recorded is a different
    decision with a different owner; it is not made by this change. An admin passes on role alone,
    which is what an admin is for.
    """
    asked = _install(monkeypatch, row=_workshop(), grant=False)
    creator = await service.load_workshop_or_404(WORKSHOP_ID, _user("RESEARCHER", uid=OWNER_ID))
    assert creator.id == WORKSHOP_ID
    admin = await service.load_workshop_or_404(WORKSHOP_ID, _user("ADMIN"))
    assert admin.id == WORKSHOP_ID
    assert asked == [], "neither the creator nor an admin should cost a grant query"


async def test_a_deleted_workshop_still_answers_409_to_a_designer_grantee(monkeypatch):
    """The ordering the loader's own comment calls "the fix rather than the style" is unchanged.

    The role clause sits INSIDE the who-may-enter test, above the deleted test, so a grantee who is
    still a designer reaches the 409 that sends them to an admin — and a demoted one gets 404 before
    it, which is the same answer a stranger gets and the same answer they got for the read.
    """
    _install(monkeypatch, row=_workshop(deleted=True), grant=True)
    with pytest.raises(HTTPException) as conflict:
        await service.load_workshop_or_404(WORKSHOP_ID, _user("DESIGNER"), for_edit=True)
    assert conflict.value.status_code == 409

    _install(monkeypatch, row=_workshop(deleted=True), grant=True)
    with pytest.raises(HTTPException) as refusal:
        await service.load_workshop_or_404(WORKSHOP_ID, _user("RESEARCHER"), for_edit=True)
    assert refusal.value.status_code == 404


# --------------------------------------------------------------------------------------
# The inspector, whose scope this must not have touched in either direction
# --------------------------------------------------------------------------------------


def test_inspector_is_outside_the_workshop_role_set_by_construction():
    """The premise the two tests below rest on, asserted rather than assumed.

    ``design_workshop_inspectors``' header states it as the trap the whole module walks around: an
    inspector holds ZERO workshop authority from their rank, and everything they have comes from a
    row in ``DesignWorkshopInspector``. If INSPECTOR ever entered this set, the loader above would
    start honouring viewer rows for them — which is the write grant that module refuses by name.
    """
    assert "INSPECTOR" not in DESIGN_WORKSHOP_ROLES
    assert sorted(DESIGN_WORKSHOP_ROLES) == ["ADMIN", "DESIGNER", "MASTER_ADMIN"]


async def test_an_inspector_reaches_the_workshop_through_their_own_loader(monkeypatch):
    """The half that must keep working: the read-only door is not this change's business.

    An inspector never reached ``load_workshop_or_404`` — their scope is a
    ``DesignWorkshopInspector`` row read by ``load_inspectable_workshop_or_404``, which has no
    ``for_edit`` parameter and is called from ``api/routes/design_workshop_inspections.py`` and
    nowhere else. Asserted at RUNTIME rather than by reading the source, because "the role gate did
    not accidentally sit in front of the inspector's door" is a behaviour.
    """

    async def _has_inspection_scope(workshop_id: str, user_id: str) -> bool:
        return True

    monkeypatch.setattr(
        inspectors, "db", SimpleNamespace(designworkshop=_WorkshopTable(_workshop()))
    )
    monkeypatch.setattr(inspectors, "has_inspection_scope", _has_inspection_scope)
    record = await inspectors.load_inspectable_workshop_or_404(WORKSHOP_ID, _user("INSPECTOR"))
    assert record.id == WORKSHOP_ID


async def test_an_inspector_holding_a_viewer_row_still_cannot_come_through_this_door(monkeypatch):
    """The pairing ``_assert_every_id_may_inspect`` refuses to create, refused from the other end too.

    A DESIGNER holding a viewer row who is later promoted to INSPECTOR is the one account that could
    hold both, and it is the exact case the appointment path names in its fourth refusal. Should one
    ever exist — a row written before that refusal, a repair script — the grant no longer opens the
    write door for them. They still read through their own loader, which is the whole design.
    """
    _install(monkeypatch, row=_workshop(), grant=True)
    with pytest.raises(HTTPException) as refusal:
        await service.load_workshop_or_404(WORKSHOP_ID, _user("INSPECTOR"), for_edit=True)
    assert refusal.value.status_code == 404
