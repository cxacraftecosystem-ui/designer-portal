"""INSPECTOR (rank 37): what the tier may do, and — the half that matters — what it may not.

WHY THIS FILE EXISTS AT ALL, RATHER THAN A ROW IN THE PERMISSION MATRIX.

Adding a tier to ``deps.ROLE_RANK`` is one line, and it grants authority through code that names no
tier at all. ``can_review_record`` is "may act on anyone ranked STRICTLY below me", so the moment
INSPECTOR was written at 37 it acquired the power to approve, reject and send back every DESIGNER's
repository records — with no line of code mentioning either role, and with no existing test going
red. An audit of this ladder flagged exactly that shape before the tier was added: a rank insert does
two things at once, and only one of them is visible in the diff.

So the answer is written down here as a DECISION rather than left to be inferred from arithmetic:

  * AN INSPECTOR MAY REVIEW A DESIGNER'S RECORDS. Wanted. It is the entire purpose of the tier, and
    it is why the rank is 37 and not 34. If a future change moves INSPECTOR below DESIGNER to "keep
    designers safe", it removes the feature.
  * AN INSPECTOR MAY NOT REWRITE THEM. ``can_edit_others_record`` is the same comparison narrowed to
    a Professor floor, and 37 < 40. Review it, send it back with notes, do not silently correct it.
    This is not a happy accident of the numbers: the two predicates were split precisely so that
    "may review" and "may rewrite" could differ, and this is the tier where they differ most visibly.
  * AN INSPECTOR GETS NO DESIGN-WORKSHOP AUTHORITY FROM THE RANK. Every workshop gate in this product
    is set membership rather than a rank floor, so 37 buys nothing there — the same position
    PROFESSOR has been in since the design-workshop surface was built. That is intended: an inspector
    inspects a report, and does not sign one. Workshop visibility for an inspector is a SCOPE
    question (a read-only, workshop-scoped grant beside ``WorkshopAssignment``, ``DataAccessGrant``,
    ``DesignWorkshopViewer`` and ``DwAccessRequest``), never a role question.

EVERY ASSERTION IS ON A PREDICATE AND NOTHING TOUCHES A DATABASE — these are pure functions of a role
string. ``tests/test_review_edit_authority.py`` drives the same review/edit distinction over the
actual HTTP route for every pair on the ladder, INSPECTOR included, and is where a route that stops
calling the predicate would be caught. This file is where the ANSWER lives.
"""

from types import SimpleNamespace

import pytest

from app.core import deps

#: The tier under test, spelled once. A constant rather than a literal in forty places so that a
#: rename shows up as one edit and not as forty silently-passing tests about a role nobody has.
INSPECTOR = "INSPECTOR"


def _user(role: str, user_id: str = "u1") -> SimpleNamespace:
    """A user row with every grantable capability OFF.

    The grants are explicit and false because several predicates here are ``rank OR grant``: with the
    flags absent, ``get_value`` answers ``None``, the assertions would still pass, and they would be
    passing for the wrong reason — a grant, not the rank. This file is about what the RANK carries.
    """
    return SimpleNamespace(
        id=user_id,
        email=f"{user_id}@example.test",
        name="Test",
        role=role,
        canReview=False,
        canManageQuestionnaire=False,
        canManageCrafts=False,
        canManageWorkshops=False,
        canDownloadDataset=False,
        canViewProvenance=False,
    )


# ────────────────────────────────────────────────────────────────────────────────────────────────
# Where the tier sits
# ────────────────────────────────────────────────────────────────────────────────────────────────


def test_the_tier_exists_at_37_strictly_between_designer_and_professor() -> None:
    """The number, and the two inequalities that give it its meaning.

    Asserted as INEQUALITIES and not only as ``== 37`` on purpose. The literal pins the value; the
    inequalities pin the INTENT, so a later renumbering that moves all three tiers together still
    has to keep the inspector above the designer it reviews and below the professor who may rewrite
    what it may only send back.
    """
    assert deps.ROLE_RANK[INSPECTOR] == 37
    assert deps.ROLE_RANK["DESIGNER"] < deps.ROLE_RANK[INSPECTOR] < deps.ROLE_RANK["PROFESSOR"]

    # The gap on BOTH sides is the reason 37 was chosen out of the free 36-39 band: a future tier can
    # be inserted on either side without renumbering anything. If this ever fails, somebody has
    # filled a gap — fine — but they should know they are spending the last one on that side.
    assert deps.ROLE_RANK[INSPECTOR] - deps.ROLE_RANK["DESIGNER"] > 1
    assert deps.ROLE_RANK["PROFESSOR"] - deps.ROLE_RANK[INSPECTOR] > 1


def test_the_label_says_reviewer_too_and_the_stored_token_does_not() -> None:
    """Inspector / Reviewer on screen; ``INSPECTOR`` in the database.

    THE NAMING RULE, PINNED, BECAUSE IT IS THE KIND OF THING A TIDYING PASS UNDOES. "Review" already
    names a different, RELATIONAL concept in this codebase — ``canReview`` is held from
    FIELD_CONTRIBUTOR upwards and means "may act on anyone below me". A tier token called REVIEWER
    would make one word mean two things, and the sentence "only a reviewer may review this" would
    stop having an answer. The LABEL carries both words because a user hunting for themselves in a
    role picker types "reviewer".
    """
    assert deps.ROLE_LABELS[INSPECTOR] == "Inspector / Reviewer"
    assert "Reviewer" in deps.ROLE_LABELS[INSPECTOR]
    assert "REVIEWER" not in deps.ROLE_RANK, (
        "a REVIEWER tier has appeared beside INSPECTOR. The token was deliberately not that word — "
        "`canReview` already owns 'review' in its relational sense. If the decision has genuinely "
        "been reversed, change it in one commit across the enum, the migration and every mirror; do "
        "not let the two spellings coexist."
    )
    # Every tier has a label. A missing one renders as the raw UPPER_SNAKE token inside an English
    # sentence, on the screen where somebody decides who may sign a ministry report.
    assert set(deps.ROLE_LABELS) == set(deps.ROLE_RANK)


# ────────────────────────────────────────────────────────────────────────────────────────────────
# THE REVIEW SIDE-EFFECT — the decision this file was written for
# ────────────────────────────────────────────────────────────────────────────────────────────────


def test_an_inspector_may_review_a_designers_records() -> None:
    """YES, AND ON PURPOSE. This is the tier's reason to exist.

    It is also the assertion that would have been true by accident. Any rank above 35 produces it,
    which is why it is stated here as an intention rather than left as arithmetic nobody chose.
    """
    assert deps.can_review_record(_user(INSPECTOR), "DESIGNER") is True


@pytest.mark.parametrize(
    "creator_role", ["DESIGNER", "RESEARCHER", "FIELD_CONTRIBUTOR", "CROWDSOURCE_VOLUNTEER"]
)
def test_an_inspector_reviews_every_tier_beneath_it(creator_role: str) -> None:
    """Not only designers. The review ladder is inclusive downwards, and an inspector is on it."""
    assert deps.can_review_record(_user(INSPECTOR), creator_role) is True


def test_an_inspector_may_not_review_a_peer_or_anyone_above() -> None:
    """STRICTLY below — an inspector is not a check on another inspector, nor on a professor.

    The peer case is the one worth pinning: ``>`` and ``>=`` differ by one character, and the
    ``>=`` version would let two inspectors approve each other's work, which is not review.
    """
    assert deps.can_review_record(_user(INSPECTOR), INSPECTOR) is False
    assert deps.can_review_record(_user(INSPECTOR), "PROFESSOR") is False
    assert deps.can_review_record(_user(INSPECTOR), "ADMIN") is False
    assert deps.can_review_record(_user(INSPECTOR), "MASTER_ADMIN") is False


def test_the_tiers_above_an_inspector_still_review_it() -> None:
    """An inspector is reviewable. A tier that reviews everyone and is answerable to nobody below
    the master admin would be a hole, not a rung."""
    assert deps.can_review_record(_user("PROFESSOR"), INSPECTOR) is True
    assert deps.can_review_record(_user("ADMIN"), INSPECTOR) is True
    assert deps.can_review_record(_user("MASTER_ADMIN"), INSPECTOR) is True
    # And the tier it reviews does not review it back.
    assert deps.can_review_record(_user("DESIGNER"), INSPECTOR) is False


def test_an_inspector_may_not_rewrite_what_it_may_review() -> None:
    """THE OTHER HALF, AND THE ONE THE RANK DOES NOT GIVE FOR FREE.

    ``can_edit_others_record`` is ``can_review_record`` narrowed to a Professor floor, and it exists
    exactly so that "may send this back" and "may silently correct this" are different powers. An
    inspector holds the first and not the second, for every tier it can review — including the
    designer whose fortnight of fieldwork is in the record.

    If this ever goes green in the other direction, an inspector has become an editor of other
    people's work, and the record's author will find their words changed with no revision they made.
    """
    for creator_role in ("DESIGNER", "RESEARCHER", "FIELD_CONTRIBUTOR", "CROWDSOURCE_VOLUNTEER"):
        assert deps.can_review_record(_user(INSPECTOR), creator_role) is True, creator_role
        assert deps.can_edit_others_record(_user(INSPECTOR), creator_role) is False, creator_role


def test_an_inspector_may_open_the_review_queue() -> None:
    """Page-level access, which is a different question from any single record.

    ``can_access_review`` is a floor at FIELD_CONTRIBUTOR — everyone with somebody beneath them — so
    this is inherited rather than granted. Pinned anyway: a tier that may act on a designer's record
    and cannot reach the screen those records are listed on would be a feature with no door.
    """
    assert deps.can_access_review(_user(INSPECTOR)) is True


# ────────────────────────────────────────────────────────────────────────────────────────────────
# What the rank deliberately does NOT buy
# ────────────────────────────────────────────────────────────────────────────────────────────────


def test_an_inspector_has_no_design_workshop_authority() -> None:
    """THE TRAP, INVERTED AND PINNED.

    Every design-workshop gate here is SET MEMBERSHIP, not a rank floor, and that is why PROFESSOR
    at 40 cannot open a design workshop today. INSPECTOR inherits exactly that position: 37 outranks
    a designer and buys nothing at all on the workshop surface.

    THIS TEST IS NOT A COMPLAINT. It is the intended rule written as an assertion, so that a future
    reader who notices an inspector cannot see a workshop reaches for the read-only workshop-scoped
    GRANT rather than for ``DESIGN_WORKSHOP_ROLES``. Adding INSPECTOR to that frozenset would not
    give an inspector read access — it would make them somebody who may WRITE every stage and sign
    the report, which is the one thing the tier was defined not to be.
    """
    assert INSPECTOR not in deps.DESIGN_WORKSHOP_ROLES
    assert deps.can_run_design_workshops(_user(INSPECTOR)) is False
    assert deps.can_create_design_workshops(_user(INSPECTOR)) is False
    assert INSPECTOR not in deps.DESIGN_WORKSHOP_CREATOR_ROLES


@pytest.mark.parametrize(
    "predicate",
    [
        "can_manage_questionnaire",
        "can_manage_crafts",
        "can_manage_workshops",
        "can_download_dataset",
        "can_manage_designer_roster",
        "can_manage_access_roster",
    ],
)
def test_an_inspector_holds_nothing_gated_at_professor_or_admin(predicate: str) -> None:
    """37 is below the Professor floor at 40, so none of these open — with no grant set.

    Parametrised over the predicate NAMES so that a failure says which power leaked. The grant flags
    are all False in ``_user``: several of these are ``rank OR grant``, and a test that passed
    because the attribute was missing would be asserting nothing about the rank.
    """
    assert getattr(deps, predicate)(_user(INSPECTOR)) is False


def test_an_inspector_is_not_an_admin() -> None:
    assert deps.is_admin(_user(INSPECTOR)) is False
    assert deps.is_master_admin(_user(INSPECTOR)) is False
    assert deps.has_rank(_user(INSPECTOR), "PROFESSOR") is False
    assert deps.has_rank(_user(INSPECTOR), "DESIGNER") is True


def test_an_inspector_may_create_records_because_the_ladder_is_inclusive() -> None:
    """A CONSEQUENCE, RECORDED RATHER THAN CHOSEN, so nobody meets it as a surprise.

    ``can_create_records`` is a floor at RESEARCHER (30), and 37 is above it, so an inspector may
    open an artisan, product, tool, process or interview. That is not a power designed for the tier —
    it falls out of the ladder being inclusive, which is the documented rule for every rank-floor
    predicate in ``deps``.

    It is asserted here so that the answer is on the record and so that a future decision to CHANGE
    it (an inspector who may only inspect, never contribute) fails this test and has to be made
    deliberately, in a place where "the ladder is inclusive" can be argued with.
    """
    assert deps.can_create_records(_user(INSPECTOR)) is True
