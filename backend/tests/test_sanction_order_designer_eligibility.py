"""THE THREE THINGS PHASE 0 OF A SANCTION ORDER HAS TO DECIDE BEFORE IT WRITES A ROW.

NO DATABASE. Every assertion here is a pure predicate, a dict, or this repository's own source read
as text — which is deliberate and not a compromise. All three defects below were found by reading
code and none of them would have been caught by exercising the happy path: each one commits SEVEN
ROWS and answers 201, and the damage shows up on somebody else's screen days later.

══ THE THREE DEFECTS, EACH OF WHICH SHIPPED ═══════════════════════════════════════════════════

1. **A RANK TEST STANDING IN FOR A SET.** ``create_from_sanction`` decides whether the named
   designer may run the workshop with ``role_rank(user) < ROLE_RANK["DESIGNER"]`` — a floor — but
   design-workshop capability is ``DESIGN_WORKSHOP_ROLES``, a SET. The roles that sit above the
   floor and outside the set (INSPECTOR 37, PROFESSOR 40, ASSISTANT_DIRECTOR 42, REGIONAL_DIRECTOR
   45, MINISTRY_ADMIN 48) kept their role and were then written a ``DesignWorkshopViewer`` row that
   ``load_workshop_or_404`` refuses to honour — its grant arm is
   ``can_run_design_workshops(user) and await has_viewer_grant(...)``, and ``and`` short-circuits
   before the row is ever read. The officer saw "the workshop is open"; the designer got a permanent
   404. Nothing in the product could undo it: both sign-in doors are lift-only, the viewers panel
   refuses the same account, ``reassign_designer`` refuses to re-name them, and the register has no
   DELETE. ``test_the_refusal_is_exactly_the_roles_the_rank_test_and_the_set_disagree_about`` is the
   census that keeps the two tests agreeing, and it is written to go RED the day an eleventh tier is
   inserted above 35 rather than to quietly let it through.

2. **AN OFFICER STANDING ON BOTH SIDES OF THE INSTRUMENT.** ``can_record_sanction_orders`` is a rank
   floor at ASSISTANT_DIRECTOR, every tier that clears it is outside ``DESIGN_WORKSHOP_ROLES`` — so
   none of them may write a single stage — and the 201 hands the caller ``credentialLink.link``, a
   working 72-hour first-password URL for the account it just minted at role DESIGNER. Naming a
   second mailbox you control and redeeming that link is author-and-approver in one request, with no
   admin involved. The comparison has to be over BOTH spellings of both addresses or a single Gmail
   dot steps over it.

3. **A MACHINE NOTE OVERWRITING AN ADMINISTRATOR'S DECISION.** ``access_roster.admit`` copies its
   ``grant`` dict wholesale onto an existing allow-list row and carves out only ``joinedAt``. The
   sanction flow handed it all four of the others unconditionally, so recording an order for
   somebody already admitted rewrote the tier an admin admitted them at, the name the admin typed,
   the admin's note, and the record of which administrator decided and when.

The tests below are the tripwires for all three. They are ALSO an order-of-operations census: a
refusal moved below ``db.tx(`` still passes its own unit test and leaves a committed orphan account,
orphan workshop and orphan admission behind on every retry.

AND A FOURTH THING, WHICH IS NOT A DEFECT IN CODE BUT WAS THE REASON THE SECOND ONE SURVIVED REVIEW.
``core/deps.py``'s ``ASSISTANT_DIRECTOR`` block — the place every permission question in this product
is answered from — stated in so many words that the tier gets "no account creation or deletion, …no
workshop-access grants, no viewer or inspector appointment", while the sanction route had been doing
all three for that tier since it landed. §5 holds the ladder to naming the exception and naming what
bounds it, because a comment that confidently states the opposite of the code is worse than none: the
reader has no way to tell, and stops looking.

THE ROW-LEVEL HALF OF ALL OF THIS IS IN ``tests/test_sanction_orders.py`` §10, which needs Postgres.
These are the assertions that can be made on any machine, which is why they are separated: a refusal
this file proves correct is still worthless if it fires after the transaction has written seven rows,
and only the DB tests can see that.
"""

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
    """The smallest thing ``role_value``, ``role_rank`` and the refusal's message can read.

    No Prisma model and no database — the same shape ``tests/test_sanction_order_gate.py::Fake``
    uses, extended with the two fields the refusal sentence names.
    """

    def __init__(self, role: str, *, name: str = "Named Person", email: str = "d@example.org"):
        self.role = role
        self.name = name
        self.email = email


class Row:
    """An ``AccessRoster`` row as the preservation helper reads it. Attributes only.

    The camel-cased keywords are the COLUMNS' OWN SPELLINGS and are deliberate: the helper reads them
    with ``getattr`` by name, so a snake_cased fixture would build an object that silently answers
    ``None`` to every one of them and make every assertion below pass for the wrong reason.
    """

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


# --------------------------------------------------------------------------------------
# 1. The rank test and the set, and the roles they disagree about
# --------------------------------------------------------------------------------------


def test_the_refusal_agrees_with_the_predicate_that_actually_decides_access():
    """DERIVED, NOT LISTED — this half stays true no matter how the ladder changes.

    The question the refusal has to answer is not "may this account hold a viewer row today" but
    "will the account this transaction LEAVES BEHIND be able to open what it is about to open". So
    the rule is: an account below DESIGNER's rank is lifted and is therefore fine; every other
    account keeps the role it has and must already be inside ``DESIGN_WORKSHOP_ROLES``.
    """
    for role in ROLE_RANK:
        will_be_lifted = ROLE_RANK[role] < ROLE_RANK["DESIGNER"]
        may_run = role in DESIGN_WORKSHOP_ROLES
        assert _refused(Account(role)) is (not will_be_lifted and not may_run), (
            f"{role} (rank {ROLE_RANK[role]}) is decided differently by the sanction flow's phase-0 "
            f"refusal than by DESIGN_WORKSHOP_ROLES, which is the set load_workshop_or_404 reads"
        )


def test_the_refusal_is_exactly_the_roles_the_rank_test_and_the_set_disagree_about():
    """THE CENSUS, AND IT IS MEANT TO GO RED ON AN ELEVENTH TIER.

    These TWO are the whole of the defect: each sits at or above DESIGNER's rank, so the lift inside
    the transaction leaves it alone, and each sits outside ``DESIGN_WORKSHOP_ROLES``, so the viewer
    row written for it cannot be honoured. A new tier inserted above 35 lands in this set by default
    — silently, with no line of code naming it, which is exactly how INSPECTOR and the three
    directorate tiers acquired the bug in the first place.

    ⚠ IT WAS FIVE UNTIL 2026-09-14 AND THE THREE THAT LEFT WERE FIXED, NOT EXCUSED. The owner ruled
    that MINISTRY_ADMIN, REGIONAL_DIRECTOR and ASSISTANT_DIRECTOR may run design workshops, so they
    entered ``DESIGN_WORKSHOP_ROLES`` — and the second half of the condition above stopped holding
    for them. A sanction order may now name one of those officers and the viewer row written for
    them IS honoured. That is this docstring's own remedy taken ("if it does, it belongs in
    DESIGN_WORKSHOP_ROLES and the answer is there, not here"), and the census shrinking is the
    evidence it worked rather than something to restore.

    IF THIS FAILS BECAUSE A TIER WAS ADDED: that is the test working. Decide whether the new tier
    runs design workshops. If it does, it belongs in ``DESIGN_WORKSHOP_ROLES`` and the answer is
    there, not here — and read ``core/deps.py``'s warning about what membership of that set confers
    before you put it there. If it does not, add it to this list and the officer gets a 422 naming
    the screen that fixes it instead of the designer getting a 404 that nothing can fix.
    """
    refused = {role for role in ROLE_RANK if _refused(Account(role))}
    assert refused == {
        "INSPECTOR",
        "PROFESSOR",
    }, f"the band of roles a sanction order cannot name has changed: {sorted(refused)}"


@pytest.mark.parametrize("role", ["CROWDSOURCE_VOLUNTEER", "FIELD_CONTRIBUTOR", "RESEARCHER"])
def test_an_account_the_flow_will_lift_is_never_refused(role: str) -> None:
    """THE MAJORITY PATH, AND THE REASON THE SIBLING RULE CANNOT BE CALLED HERE.

    ``design_workshops.assert_every_designer_may_be_named`` is what every OTHER writer of a viewer
    row calls, and calling it in phase 0 is the obvious "fix" that would break this: its role arm
    would refuse every researcher, and a researcher named on a sanction order is the ordinary case
    ``test_sanction_orders.py::test_an_existing_account_is_reused_and_never_duplicated`` pins.
    """
    assert _refused(Account(role)) is False


@pytest.mark.parametrize("role", sorted(DESIGN_WORKSHOP_ROLES))
def test_an_account_that_can_already_run_workshops_is_never_refused(role: str) -> None:
    """ADMIN included, and that is not an oversight. An admin who is the practising designer of a
    cluster is a legitimate answer — ``assert_every_designer_may_be_named``'s own docstring says so —
    and ``test_an_admin_named_on_a_sanction_order_is_not_demoted`` pins that they keep their tier."""
    assert _refused(Account(role)) is False


def test_no_existing_account_is_never_refused():
    """``None`` means the transaction mints the account itself, at role DESIGNER. Refusing here would
    refuse every first-time designer, which is the entire feature."""
    assert _refused(None) is False


def test_the_refusal_names_the_role_the_account_actually_holds():
    """An officer cannot change anybody's role, so the sentence has to name what is wrong AND the
    screen that fixes it. "This person cannot run a workshop" with no reason reads as a bug in the
    portal rather than as a fact about the account, and the officer files a ticket instead of
    walking to an admin."""
    with pytest.raises(HTTPException) as caught:
        _refuse_if_the_named_account_cannot_run_the_workshop(
            Account("PROFESSOR", name="Prof. Rao", email="rao@example.org")
        )
    detail = str(caught.value.detail)
    assert "PROFESSOR" in detail
    assert "Prof. Rao" in detail and "rao@example.org" in detail
    assert "users screen" in detail, "the officer is not told where the fix lives"


# --------------------------------------------------------------------------------------
# 2. The officer who names themselves
# --------------------------------------------------------------------------------------


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
    """THE HALF A BARE STRING COMPARE WOULD MISS, and the reason this compares key SETS.

    ``canonical_email`` strips Gmail dots and the ``+`` suffix, which is exactly how the same person
    reaches this route under two spellings. A guard any Gmail user can step over by typing one dot is
    not a guard, and this module canonicalises everywhere else for precisely this reason.
    """
    assert _self_named("a.k.officer@gmail.com", "akofficer+design@gmail.com") is True
    assert _self_named("akofficer@gmail.com", "a.k.officer@gmail.com") is True


def test_an_officer_naming_somebody_else_is_not_refused():
    assert _self_named("officer@example.org", "designer@example.org") is False
    assert _self_named("officer@gmail.com", "officer@example.org") is False


def test_an_officer_with_no_readable_address_refuses_nobody():
    """FAIL OPEN HERE AND ONLY HERE, on purpose — and it is a decision, not an accident of ``set``.

    ``email_match_keys`` answers ``[]`` for an address it cannot use, and the refusal declines to
    claim that an officer whose mailbox could not be read has named themselves. Nothing is let
    through by it: the designer's OWN address is refused by the ``if not keys`` check immediately
    above this call in phase 0, so the only case this reaches is a ``User`` row with no readable
    address, which is not a state this product's sign-in doors can produce.
    """
    assert _self_named(None, "designer@example.org") is False


def test_the_refusal_tells_the_officer_who_can_record_it_instead():
    with pytest.raises(HTTPException) as caught:
        _refuse_if_the_officer_named_themselves(
            Account("ASSISTANT_DIRECTOR", email="officer@example.org"), _keys("officer@example.org")
        )
    detail = str(caught.value.detail)
    assert "Assistant Director" in detail, "the officer is not told who to ask"


# --------------------------------------------------------------------------------------
# 3. The allow-list row an administrator already decided about
# --------------------------------------------------------------------------------------


def test_a_new_address_is_admitted_with_all_four_fields():
    """Nothing to preserve. The tier, the name, the reason and the decision are what an admission
    IS, and ``test_sanction_orders.py::test_five_fields_produce_seven_rows`` asserts all of them on
    the row this produces."""
    assert _an_admission_that_preserves_an_admins_decision(
        None, designer_name="Sandhya", note=NOTE
    ) == {"admit_role": "DESIGNER", "full_name": "Sandhya", "note": NOTE, "decided": True}


def test_an_administrators_row_keeps_its_tier_its_name_and_who_decided_it():
    """THE DEFECT, IN ONE ASSERTION. A professor admitted at PROFESSOR with an admin's own note used
    to come out of this call as a DESIGNER named by whatever the officer typed, with the admin's note
    gone and a ministry officer recorded as the deciding administrator."""
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
    # The note is the one field that is ADDED to rather than withheld: both facts are worth keeping.
    assert admission["note"] == (
        "Admitted 2025-04 on Prof. Rao's request; craft taxonomy owner. " + NOTE
    )


@pytest.mark.parametrize("stored", ["CROWDSOURCE_VOLUNTEER", "FIELD_CONTRIBUTOR", "RESEARCHER"])
def test_a_tier_below_designer_is_lifted_rather_than_preserved(stored: str) -> None:
    """LIFT, NEVER LOWER — the same comparison, on the other row.

    Preserving the stored tier unconditionally was the other candidate and it is worse here: the
    transaction lifts this person's ACCOUNT to DESIGNER, so an allow-list row left saying RESEARCHER
    makes /admin/access and /admin/users disagree about the same person on the same morning, and
    ``GET /access/roster?roles=DESIGNER`` misses the designer a sanction order just admitted.
    ``admitRole``'s own column comment in ``prisma/schema.prisma`` states this rule.
    """
    admission = _an_admission_that_preserves_an_admins_decision(
        Row(admitRole=stored, fullName="Sandhya", decidedById="admin-1"),
        designer_name="Sandhya Devi",
        note=NOTE,
    )
    assert admission["admit_role"] == "DESIGNER"
    # ...and the three fields that are NOT a tier are still the administrator's.
    assert admission["full_name"] is None
    assert admission["decided"] is False


@pytest.mark.parametrize("stored", ["ADMIN", "MASTER_ADMIN", "MINISTRY_ADMIN"])
def test_a_tier_above_designer_is_never_lowered(stored: str) -> None:
    """The other half of the same comparison. An admin admitted as an ADMIN is not written down to
    DESIGNER because a colleague recorded a sanction order naming them."""
    assert (
        _an_admission_that_preserves_an_admins_decision(
            Row(admitRole=stored), designer_name="Sandhya", note=NOTE
        )["admit_role"]
        is None
    )


def test_a_row_with_nothing_on_it_is_filled_in_rather_than_left_empty():
    """PRESERVE, NOT REFUSE TO WRITE. The grandfathered admissions carry no tier, no name and no
    decision — withholding all four from them would leave a designer admitted by a sanction order
    with a blank row on /admin/access and nothing saying how they got there."""
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
    """IDEMPOTENCE FOR A RETRY. The officer's browser resending the same create, or a second order
    for the same number, must not stack the same sentence."""
    assert (
        _an_admission_that_preserves_an_admins_decision(
            Row(notes=NOTE), designer_name="Sandhya", note=NOTE
        )["note"]
        is None
    )


def test_a_second_sanction_order_for_the_same_designer_appends_rather_than_replaces():
    """Two orders are two facts. ``sanctionOrderKey`` is unique, so repeats over time are distinct
    sentences on an untruncated column rather than unbounded growth from one order."""
    assert (
        _an_admission_that_preserves_an_admins_decision(
            Row(notes=NOTE), designer_name="Sandhya", note=OTHER_NOTE
        )["note"]
        == f"{NOTE} {OTHER_NOTE}"
    )


def test_the_helper_decides_every_field_admit_would_otherwise_overwrite():
    """THE TRIPWIRE FOR ``admit`` GROWING A FIFTH COLUMN.

    ``access_roster.admit`` builds one ``grant`` dict from its keyword arguments and copies it
    wholesale onto an existing row; ``joinedAt`` is the only column it protects for itself. So every
    keyword it takes except the address, the actor and the transaction client is a column this
    caller has to make a decision about. The day a sixth keyword is added — an ``expires_at``, a
    ``source`` — this goes red, and the question to answer is whether a sanction order may overwrite
    it on a row an administrator has already decided about.
    """
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


# --------------------------------------------------------------------------------------
# 4. Order of operations — the half a unit test cannot see
# --------------------------------------------------------------------------------------


def test_every_phase_zero_refusal_is_asked_above_the_transaction():
    """ORDER, NOT PRESENCE — the same assertion ``test_design_workshop_creation_path`` makes about
    the shared opener, for the same reason.

    Asked from inside ``db.tx(``, each of these raises after rows are already written. The rollback
    is correct, so nothing is left behind — but the officer's 422 arrives from the middle of a
    seven-row write they cannot distinguish from a 500, and a refusal that fires after the workshop
    row would be reading the rows this transaction is itself writing. Both are silent.
    """
    source = inspect.getsource(create_from_sanction)
    opens = source.index("async with db.tx(")
    for refusal in (
        "_refuse_if_the_officer_named_themselves(",
        "_refuse_if_number_taken(",
        "_the_allow_list_row_or_refuse_if_barred(",
        "_refuse_if_empanelment_ended(",
        "_refuse_if_the_named_account_cannot_run_the_workshop(",
        "_an_admission_that_preserves_an_admins_decision(",
    ):
        assert refusal in source, f"{refusal} is no longer called from create_from_sanction"
        assert source.index(refusal) < opens, f"{refusal} is asked from inside the transaction"


def test_the_sibling_eligibility_rule_is_not_called_here():
    """THE TEMPTING EDIT, REFUSED IN WRITING.

    ``assert_every_designer_may_be_named`` is the rule every other writer of a viewer row calls, and
    a reader who notices this flow does not call it will reach for it. Two of its three arms ask
    about rows THIS transaction is about to write: from inside the transaction they read through the
    module singleton, see nothing, and refuse every sanction order ever recorded; from phase 0 they
    refuse every researcher this flow is about to lift and every designer it is about to empanel.
    The third arm — the allow-list bar — is already asked, earlier and with a better sentence.

    Only the CALL is forbidden; the name appears in this module's comments explaining exactly this,
    which is why the assertion is on the call spelling and not on the bare name.
    """
    source = inspect.getsource(sanction_orders)
    assert "await assert_every_designer_may_be_named(" not in source
    assert "await design_workshops.assert_every_designer_may_be_named(" not in source


# --------------------------------------------------------------------------------------
# 5. The ladder's own comment, which described the opposite of what the code does
# --------------------------------------------------------------------------------------


def test_the_role_ladder_records_the_sanction_exception_rather_than_denying_it():
    """A COMMENT THAT CONFIDENTLY STATES THE OPPOSITE OF THE CODE IS WORSE THAN NO COMMENT.

    ``core/deps.py``'s ``ASSISTANT_DIRECTOR`` block is where this product's permission questions get
    answered, and the "WHAT IT DELIBERATELY DOES NOT BUY" sentence committed on 2026-09-13 listed
    "no account creation or deletion", "no workshop-access grants" and "no viewer or inspector
    appointment" — three things ``POST /api/sanction-orders`` had already been doing for this exact
    tier. A reviewer checking whether a rank-42 account may mint a designer would have read that
    sentence and stopped.

    So the block has to name the exception AND name what bounds it, and this is the tripwire that
    keeps it doing so: a later "tidy-up" that restores the absolute wording, or that deletes the
    self-naming refusal without amending the paragraph that leans on it, goes red here.

    THIS IS A CLAIM ABOUT PROSE, which nothing can verify — so it checks the two things that can be:
    that the three false clauses are gone, and that the refusal the paragraph cites is named by the
    symbol a reader can grep for.
    """
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
