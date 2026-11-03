from __future__ import annotations

import inspect
from pathlib import Path
from typing import Any

import pytest
from fastapi import HTTPException

from app.core.deps import DESIGN_WORKSHOP_ROLES, ROLE_RANK
from app.services import access_roster, sanction_orders
from app.services.designers import canonical_email, normalise_email
from app.services.sanction_orders import (
    SANCTION_ADMISSION_NOTE,
    _an_admission_that_preserves_an_admins_decision,
    _refuse_if_the_named_account_cannot_run_the_workshop,
    _refuse_if_the_officer_named_themselves,
    create_from_sanction,
)

BACKEND = Path(__file__).resolve().parents[1]

NOTE = SANCTION_ADMISSION_NOTE.format(no="SO/2026/42", officer="An Officer")
OTHER_NOTE = SANCTION_ADMISSION_NOTE.format(no="SO/2027/7", officer="An Officer")


class Account:
    def __init__(self, role: str, *, name: str = "Named Person", email: str = "d@example.org"):
        self.role = role
        self.name = name
        self.email = email


class Row:
    def __init__(
        self,
        *,
        admitRole: str | None = None,
        fullName: str | None = None,
        notes: str | None = None,
        decidedById: str | None = None,
        decidedAt: Any = None,
    ) -> None:
        self.admitRole = admitRole
        self.fullName = fullName
        self.notes = notes
        self.decidedById = decidedById
        self.decidedAt = decidedAt


def _refused(user: Any) -> bool:
    try:
        _refuse_if_the_named_account_cannot_run_the_workshop(user)
    except HTTPException as exc:
        assert exc.status_code == 422
        return True
    return False



def test_the_refusal_agrees_with_the_predicate_that_actually_decides_access():
    for role in ROLE_RANK:
        will_be_lifted = ROLE_RANK[role] < ROLE_RANK["DESIGNER"]
        may_run = role in DESIGN_WORKSHOP_ROLES
        assert _refused(Account(role)) is (not will_be_lifted and not may_run), (
            f"{role} (rank {ROLE_RANK[role]}) is decided differently by the sanction flow's phase-0 "
            f"refusal than by DESIGN_WORKSHOP_ROLES, which is the set load_workshop_or_404 reads"
        )


def test_the_refusal_is_exactly_the_roles_the_rank_test_and_the_set_disagree_about():
    refused = {role for role in ROLE_RANK if _refused(Account(role))}
    assert refused == {
        "INSPECTOR",
        "PROFESSOR",
    }, f"the band of roles a sanction order cannot name has changed: {sorted(refused)}"


@pytest.mark.parametrize("role", ["CROWDSOURCE_VOLUNTEER", "FIELD_CONTRIBUTOR", "RESEARCHER"])
def test_an_account_the_flow_will_lift_is_never_refused(role: str) -> None:
    assert _refused(Account(role)) is False


@pytest.mark.parametrize("role", sorted(DESIGN_WORKSHOP_ROLES))
def test_an_account_that_can_already_run_workshops_is_never_refused(role: str) -> None:
    assert _refused(Account(role)) is False


def test_no_existing_account_is_never_refused():
    assert _refused(None) is False


def test_the_refusal_names_the_role_the_account_actually_holds():
    with pytest.raises(HTTPException) as caught:
        _refuse_if_the_named_account_cannot_run_the_workshop(
            Account("PROFESSOR", name="Prof. Rao", email="rao@example.org")
        )
    detail = str(caught.value.detail)
    assert "PROFESSOR" in detail
    assert "Prof. Rao" in detail and "rao@example.org" in detail
    assert "users screen" in detail, "the officer is not told where the fix lives"



def _keys(address: str) -> list[str]:
    literal = normalise_email(address)
    canonical = canonical_email(literal)
    return [literal] if canonical == literal else [literal, canonical]


def _self_named(officer_email: str | None, designer_email: str) -> bool:
    try:
        _refuse_if_the_officer_named_themselves(
            Account("ASSISTANT_DIRECTOR", email=officer_email), _keys(designer_email)
        )
    except HTTPException as exc:
        assert exc.status_code == 422
        return True
    return False


def test_an_officer_may_not_name_their_own_address():
    assert _self_named("officer@example.org", "officer@example.org") is True


def test_an_officer_may_not_name_an_alias_of_their_own_mailbox():
    assert _self_named("a.k.officer@gmail.com", "akofficer+design@gmail.com") is True
    assert _self_named("akofficer@gmail.com", "a.k.officer@gmail.com") is True


def test_an_officer_naming_somebody_else_is_not_refused():
    assert _self_named("officer@example.org", "designer@example.org") is False
    assert _self_named("officer@gmail.com", "officer@example.org") is False


def test_an_officer_with_no_readable_address_refuses_nobody():
    assert _self_named(None, "designer@example.org") is False


def test_the_refusal_tells_the_officer_who_can_record_it_instead():
    with pytest.raises(HTTPException) as caught:
        _refuse_if_the_officer_named_themselves(
            Account("ASSISTANT_DIRECTOR", email="officer@example.org"), _keys("officer@example.org")
        )
    detail = str(caught.value.detail)
    assert "Assistant Director" in detail, "the officer is not told who to ask"



def test_a_new_address_is_admitted_with_all_four_fields():
    assert _an_admission_that_preserves_an_admins_decision(
        None, designer_name="Sandhya", note=NOTE
    ) == {"admit_role": "DESIGNER", "full_name": "Sandhya", "note": NOTE, "decided": True}


def test_an_administrators_row_keeps_its_tier_its_name_and_who_decided_it():
    admission = _an_admission_that_preserves_an_admins_decision(
        Row(
            admitRole="PROFESSOR",
            fullName="Prof. S. Rao",
            notes="Admitted 2025-04 on Prof. Rao's request; craft taxonomy owner.",
            decidedById="admin-1",
        ),
        designer_name="S Rao",
        note=NOTE,
    )
    assert admission["admit_role"] is None, "the tier an admin admitted them at was overwritten"
    assert admission["full_name"] is None, "the admin's own spelling of the name was overwritten"
    assert admission["decided"] is False, "which administrator decided, and when, was overwritten"
    assert admission["note"] == (
        "Admitted 2025-04 on Prof. Rao's request; craft taxonomy owner. " + NOTE
    )


@pytest.mark.parametrize("stored", ["CROWDSOURCE_VOLUNTEER", "FIELD_CONTRIBUTOR", "RESEARCHER"])
def test_a_tier_below_designer_is_lifted_rather_than_preserved(stored: str) -> None:
    admission = _an_admission_that_preserves_an_admins_decision(
        Row(admitRole=stored, fullName="Sandhya", decidedById="admin-1"),
        designer_name="Sandhya Devi",
        note=NOTE,
    )
    assert admission["admit_role"] == "DESIGNER"
    assert admission["full_name"] is None
    assert admission["decided"] is False


@pytest.mark.parametrize("stored", ["ADMIN", "MASTER_ADMIN", "MINISTRY_ADMIN"])
def test_a_tier_above_designer_is_never_lowered(stored: str) -> None:
    assert (
        _an_admission_that_preserves_an_admins_decision(
            Row(admitRole=stored), designer_name="Sandhya", note=NOTE
        )["admit_role"]
        is None
    )


def test_a_row_with_nothing_on_it_is_filled_in_rather_than_left_empty():
    admission = _an_admission_that_preserves_an_admins_decision(
        Row(notes="   "), designer_name="Sandhya", note=NOTE
    )
    assert admission == {
        "admit_role": "DESIGNER",
        "full_name": "Sandhya",
        "note": NOTE,
        "decided": True,
    }


def test_a_row_already_carrying_this_orders_note_is_not_annotated_twice():
    assert (
        _an_admission_that_preserves_an_admins_decision(
            Row(notes=NOTE), designer_name="Sandhya", note=NOTE
        )["note"]
        is None
    )


def test_a_second_sanction_order_for_the_same_designer_appends_rather_than_replaces():
    assert (
        _an_admission_that_preserves_an_admins_decision(
            Row(notes=NOTE), designer_name="Sandhya", note=OTHER_NOTE
        )["note"]
        == f"{NOTE} {OTHER_NOTE}"
    )


def test_the_helper_decides_every_field_admit_would_otherwise_overwrite():
    decided_here = set(
        _an_admission_that_preserves_an_admins_decision(None, designer_name="x", note="y")
    )
    admits = set(inspect.signature(access_roster.admit).parameters) - {
        "email",
        "actor_id",
        "client",
    }
    assert decided_here == admits, (
        f"access_roster.admit takes {sorted(admits)} but the sanction flow decides "
        f"{sorted(decided_here)}; an undecided field is written unconditionally"
    )



def test_every_phase_zero_refusal_is_asked_above_the_transaction():
    create = inspect.getsource(create_from_sanction)
    opens = create.index("async with db.tx(")

    for refusal in ("self_named_reason(", "_refuse_if_number_taken("):
        assert refusal in create, f"{refusal} is no longer called from create_from_sanction"
        assert create.index(refusal) < opens, f"{refusal} is asked from inside the transaction"

    assert "resolve_named_designer(" in create, "the per-designer phase-0 chain is not called"
    assert create.index("resolve_named_designer(") < opens, "the chain is entered inside the tx"

    chain = inspect.getsource(sanction_orders.designer_standing_verdict) + inspect.getsource(
        sanction_orders.resolve_named_designer
    )
    for refusal in (
        "self_named_reason(",
        "allow_list_verdict(",
        "empanelment_ended_reason(",
        "account_verdict(",
        "register_account_verdict(",
        "cannot_run_reason(",
        "_an_admission_that_preserves_an_admins_decision(",
    ):
        assert refusal in chain, f"{refusal} is no longer part of the phase-0 chain"

    inside = create[opens:]
    for refusal in (
        "self_named_reason(",
        "_refuse_if_number_taken(",
        "duplicate_reason(",
        "allow_list_verdict(",
        "_the_allow_list_row_or_refuse_if_barred(",
        "empanelment_ended_reason(",
        "_refuse_if_empanelment_ended(",
        "account_verdict(",
        "_existing_account(",
        "register_account_verdict(",
        "cannot_run_reason(",
        "_refuse_if_the_named_account_cannot_run_the_workshop(",
        "resolve_named_designer(",
        "designer_standing_verdict(",
    ):
        assert refusal not in inside, f"{refusal} is asked from inside the transaction"


def test_the_sibling_eligibility_rule_is_not_called_here():
    source = inspect.getsource(sanction_orders)
    assert "await assert_every_designer_may_be_named(" not in source
    assert "await design_workshops.assert_every_designer_may_be_named(" not in source



def test_the_role_ladder_records_the_sanction_exception_rather_than_denying_it():
    deps = (BACKEND / "app" / "core" / "deps.py").read_text(encoding="utf-8")
    block = deps[deps.index('"PROFESSOR": 40,') : deps.index('"ASSISTANT_DIRECTOR": 42,')]

    for denied in (
        "no account creation or deletion",
        "no workshop-access grants",
        "no viewer or inspector appointment",
    ):
        assert denied not in block, (
            f"core/deps.py's ASSISTANT_DIRECTOR block claims {denied!r}, which "
            f"POST /api/sanction-orders has made false for this tier since it landed"
        )
    assert "sanction" in block.lower(), "the ladder does not mention the one door that widens it"
    assert "_refuse_if_the_officer_named_themselves" in block, (
        "the ladder names the exception but not the refusal that bounds it, so a reader who deletes "
        "that refusal has nothing telling them what it was holding up"
    )


@pytest.mark.parametrize("spelling", ["new.master@gmail.com", "newmaster+old.order@gmail.com"])
async def test_no_link_is_reissued_for_an_account_on_the_master_admins_mailbox(
    monkeypatch, spelling
):
    from types import SimpleNamespace

    from app.core.config import get_settings

    monkeypatch.setattr(get_settings(), "master_admin_email", "new.master@gmail.com")
    planted = SimpleNamespace(
        id="acct-planted",
        email=spelling,
        name="Planted By An Old Order",
        role="DESIGNER",
        authProvider="LOCAL",
        passwordHash="a-hash-the-register-minted",
    )

    class _Users:
        async def find_unique(self, where):
            return planted if where == {"id": planted.id} else None

    async def _never(**_kwargs):
        raise AssertionError("a link was minted for the master admin's mailbox")

    monkeypatch.setattr(sanction_orders, "db", SimpleNamespace(user=_Users()))
    monkeypatch.setattr(sanction_orders.credential_links, "issue_link", _never)
    row = SimpleNamespace(accountCreated=True, designerUserId=planted.id)
    officer = SimpleNamespace(
        id="acct-officer", role="ASSISTANT_DIRECTOR", email="officer@ministry.gov.in"
    )
    with pytest.raises(HTTPException) as refused:
        await sanction_orders.reissue_credential_link(row, officer)
    assert refused.value.status_code == 422
    assert refused.value.detail == sanction_orders.SANCTION_MASTER_MAILBOX
