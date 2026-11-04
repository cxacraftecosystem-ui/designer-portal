from __future__ import annotations

from types import SimpleNamespace
from typing import Any

import pytest
from fastapi import HTTPException

from app.core.deps import DESIGN_WORKSHOP_ROLES
from app.services import design_workshop_inspectors as inspectors, design_workshops as service

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
    def __init__(self, row: Any) -> None:
        self.row = row
        self.finds: list[dict[str, Any]] = []

    async def find_unique(self, **kwargs: Any) -> Any:
        self.finds.append(kwargs)
        return self.row


def _install(monkeypatch: Any, *, row: Any, grant: bool) -> list[tuple[str, str]]:
    asked: list[tuple[str, str]] = []

    async def _has_viewer_grant(workshop_id: str, user_id: str) -> bool:
        asked.append((workshop_id, user_id))
        return grant

    monkeypatch.setattr(service, "db", SimpleNamespace(designworkshop=_WorkshopTable(row)))
    monkeypatch.setattr(service, "has_viewer_grant", _has_viewer_grant)
    return asked



@pytest.mark.parametrize("role", ["RESEARCHER", "PROFESSOR", "FIELD_CONTRIBUTOR", "INSPECTOR"])
async def test_a_grant_is_not_honoured_once_the_account_leaves_the_workshop_roles(
    monkeypatch, role
):
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
    asked = _install(monkeypatch, row=_workshop(), grant=True)
    record = await service.load_workshop_or_404(WORKSHOP_ID, _user("DESIGNER"))
    assert record.id == WORKSHOP_ID
    assert asked == [(WORKSHOP_ID, "u-grantee")]


async def test_a_designer_grantee_still_reaches_the_edit_door(monkeypatch):
    _install(monkeypatch, row=_workshop(), grant=True)
    record = await service.load_workshop_or_404(WORKSHOP_ID, _user("DESIGNER"), for_edit=True)
    assert record.id == WORKSHOP_ID


async def test_a_designer_without_a_grant_is_still_refused(monkeypatch):
    _install(monkeypatch, row=_workshop(), grant=False)
    with pytest.raises(HTTPException) as refusal:
        await service.load_workshop_or_404(WORKSHOP_ID, _user("DESIGNER"))
    assert refusal.value.status_code == 404


async def test_the_creator_and_the_admin_arms_are_untouched(monkeypatch):
    asked = _install(monkeypatch, row=_workshop(), grant=False)
    creator = await service.load_workshop_or_404(WORKSHOP_ID, _user("RESEARCHER", uid=OWNER_ID))
    assert creator.id == WORKSHOP_ID
    admin = await service.load_workshop_or_404(WORKSHOP_ID, _user("ADMIN"))
    assert admin.id == WORKSHOP_ID
    assert asked == [], "neither the creator nor an admin should cost a grant query"


async def test_a_deleted_workshop_still_answers_409_to_a_designer_grantee(monkeypatch):
    _install(monkeypatch, row=_workshop(deleted=True), grant=True)
    with pytest.raises(HTTPException) as conflict:
        await service.load_workshop_or_404(WORKSHOP_ID, _user("DESIGNER"), for_edit=True)
    assert conflict.value.status_code == 409

    _install(monkeypatch, row=_workshop(deleted=True), grant=True)
    with pytest.raises(HTTPException) as refusal:
        await service.load_workshop_or_404(WORKSHOP_ID, _user("RESEARCHER"), for_edit=True)
    assert refusal.value.status_code == 404



def test_inspector_is_outside_the_workshop_role_set_by_construction():
    assert "INSPECTOR" not in DESIGN_WORKSHOP_ROLES
    assert "PROFESSOR" not in DESIGN_WORKSHOP_ROLES


async def test_an_inspector_reaches_the_workshop_through_their_own_loader(monkeypatch):
    async def _has_inspection_scope(workshop_id: str, user_id: str) -> bool:
        return True

    monkeypatch.setattr(
        inspectors, "db", SimpleNamespace(designworkshop=_WorkshopTable(_workshop()))
    )
    monkeypatch.setattr(inspectors, "has_inspection_scope", _has_inspection_scope)
    record = await inspectors.load_inspectable_workshop_or_404(WORKSHOP_ID, _user("INSPECTOR"))
    assert record.id == WORKSHOP_ID


async def test_an_inspector_holding_a_viewer_row_still_cannot_come_through_this_door(monkeypatch):
    _install(monkeypatch, row=_workshop(), grant=True)
    with pytest.raises(HTTPException) as refusal:
        await service.load_workshop_or_404(WORKSHOP_ID, _user("INSPECTOR"), for_edit=True)
    assert refusal.value.status_code == 404



def _posts_held(monkeypatch: Any, held: dict[str, frozenset[str]]) -> list[str]:
    from app.services import design_workshop_posts

    asked: list[str] = []

    async def _among(workshop_id: str, user_ids: Any) -> dict[str, frozenset[str]]:
        ids = list(user_ids)
        asked.extend(ids)
        return {uid: held[uid] for uid in ids if uid in held}

    monkeypatch.setattr(design_workshop_posts, "supervisory_posts_among", _among)
    return asked


@pytest.mark.parametrize(
    ("role", "post", "named"),
    [
        ("ADMIN", "INSPECTOR", "inspector"),
        ("MASTER_ADMIN", "ASSISTANT_DIRECTOR", "Assistant Director"),
        ("ADMIN", "REGIONAL_DIRECTOR", "Regional Director"),
    ],
)
async def test_an_admin_holding_a_post_on_the_workshop_cannot_write_it(monkeypatch, role, post, named):
    _install(monkeypatch, row=_workshop(), grant=False)
    _posts_held(monkeypatch, {"u-grantee": frozenset({post})})
    with pytest.raises(HTTPException) as refusal:
        await service.load_workshop_or_404(WORKSHOP_ID, _user(role), for_edit=True)
    assert refusal.value.status_code == 403
    assert f"You are this workshop's {named}" in refusal.value.detail
    assert "does not write it" in refusal.value.detail

    record = await service.load_workshop_or_404(WORKSHOP_ID, _user(role))
    assert record.id == WORKSHOP_ID, "holding a post must not cost the holder their READ"


async def test_an_admin_with_no_post_on_the_workshop_still_writes_it(monkeypatch):
    _install(monkeypatch, row=_workshop(), grant=False)
    asked = _posts_held(monkeypatch, {})
    record = await service.load_workshop_or_404(WORKSHOP_ID, _user("ADMIN"), for_edit=True)
    assert record.id == WORKSHOP_ID
    assert asked == ["u-grantee"], "the posts were not asked about for a role that may hold one"


async def test_a_designers_save_pays_no_query_for_the_post_check(monkeypatch):
    _install(monkeypatch, row=_workshop(), grant=True)
    asked = _posts_held(monkeypatch, {"u-grantee": frozenset({"INSPECTOR"})})
    record = await service.load_workshop_or_404(WORKSHOP_ID, _user("DESIGNER"), for_edit=True)
    assert record.id == WORKSHOP_ID
    assert asked == [], "a designer's save queried the post tables"



@pytest.mark.parametrize(("role", "admitted"), [("DESIGNER", True), ("RESEARCHER", False)])
async def test_a_demoted_viewer_can_no_longer_print_join_cards(monkeypatch, role, admitted):
    from app.services import design_workshop_grants as grants

    async def _has_viewer_grant(workshop_id: str, user_id: str) -> bool:
        return True

    monkeypatch.setattr(grants, "db", SimpleNamespace(designworkshop=_WorkshopTable(_workshop())))
    monkeypatch.setattr(grants, "has_viewer_grant", _has_viewer_grant)
    if admitted:
        record = await grants._workshop_for_issuer_or_404(WORKSHOP_ID, _user(role))
        assert record.id == WORKSHOP_ID
        return
    with pytest.raises(HTTPException) as refusal:
        await grants._workshop_for_issuer_or_404(WORKSHOP_ID, _user(role))
    assert (refusal.value.status_code, refusal.value.detail) == (404, "Record not found")



class _CapturingUsers:
    def __init__(self) -> None:
        self.where: dict[str, Any] = {}

    async def find_many(self, **kwargs: Any) -> list[Any]:
        self.where = kwargs.get("where") or {}
        return []


def _roles_offered(where: dict[str, Any]) -> set[str]:
    clauses = where["AND"]
    in_list = next(
        (set(c["role"]["in"]) for c in clauses if isinstance(c.get("role"), dict)), None
    )
    arms = next((c["OR"] for c in clauses if "OR" in c and any("role" in str(a) for a in c["OR"])), [])
    offered: set[str] = set()
    for arm in arms:
        if isinstance(arm.get("role"), dict):
            offered |= set(arm["role"]["in"])
        for inner in arm.get("AND", []):
            if isinstance(inner.get("role"), str):
                offered.add(inner["role"])
    return offered & in_list if in_list is not None else offered


async def test_the_viewer_picker_offers_every_role_the_viewer_write_accepts(monkeypatch):
    from app.core.deps import ROLE_RANK
    from app.services import access_roster, design_workshop_viewers as viewers

    users = _CapturingUsers()

    async def _roster() -> tuple[list[str], bool]:
        return ["d@example.org"], False

    async def _barred() -> list[str]:
        return []

    monkeypatch.setattr(viewers, "db", SimpleNamespace(user=users))
    monkeypatch.setattr(viewers, "active_roster_emails", _roster)
    monkeypatch.setattr(access_roster, "barred_emails", _barred)
    await viewers.eligible_viewers()
    picker = _roles_offered(users.where)

    accounts = {
        f"u-{role}": SimpleNamespace(
            id=f"u-{role}", name=role, email=f"{role.lower()}@x.test", role=role
        )
        for role in ROLE_RANK
    }

    class _Users:
        async def find_many(self, **kwargs: Any) -> list[Any]:
            wanted = kwargs["where"]["id"]["in"]
            return [accounts[uid] for uid in wanted if uid in accounts]

    async def _admits_every_designer(found: list[Any]) -> set[str]:
        return {u.email for u in found}

    async def _bars_nobody(emails: list[str]) -> set[str]:
        return set()

    monkeypatch.setattr(viewers, "db", SimpleNamespace(user=_Users()))
    monkeypatch.setattr(viewers, "_designers_the_roster_still_admits", _admits_every_designer)
    monkeypatch.setattr(access_roster, "barred_among", _bars_nobody)
    accepted: set[str] = set()
    for uid, account in accounts.items():
        try:
            await viewers._assert_every_id_may_be_granted({uid})
        except HTTPException as refusal:
            assert "cannot run a design & prototype workshop" in str(refusal.detail), refusal.detail
            continue
        accepted.add(account.role)

    assert accepted == set(DESIGN_WORKSHOP_ROLES)
    assert picker == accepted, (
        f"the picker offers {sorted(picker)} and the write accepts {sorted(accepted)}"
    )


async def test_the_designer_directory_offers_every_role_the_viewer_write_accepts(monkeypatch):
    from app.services import design_workshop_viewers as viewers, designers

    users = _CapturingUsers()

    async def _roster() -> tuple[list[str], bool]:
        return ["d@example.org"], False

    monkeypatch.setattr(designers, "db", SimpleNamespace(user=users))
    monkeypatch.setattr(viewers, "active_roster_emails", _roster)

    await designers.workshop_capable_accounts()
    assert _roles_offered(users.where) == set(DESIGN_WORKSHOP_ROLES)

    await designers.workshop_capable_accounts(include_admins=False)
    assert _roles_offered(users.where) == {"DESIGNER"}, (
        "the sanction register's picker has widened past the empanelled designer roster"
    )
