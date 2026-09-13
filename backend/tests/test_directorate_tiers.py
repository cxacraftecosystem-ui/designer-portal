"""The three DIRECTORATE tiers (ASSISTANT_DIRECTOR 42, REGIONAL_DIRECTOR 45, MINISTRY_ADMIN 48).

WHY ONE FILE FOR THREE TIERS, AND WHY A FILE AT ALL.

They landed in one wave on 2026-09-13 and they share one argument, so they are parametrised rather
than triplicated. The reason the file exists is the same reason ``tests/test_inspector_tier.py``
exists, one band higher and considerably larger: adding a tier to ``deps.ROLE_RANK`` is three lines,
and it grants authority through code that names no tier at all.

WHAT A RANK ABOVE 40 BUYS, WHICH IS STRICTLY MORE THAN ANY EARLIER INSERT BOUGHT. Every tier added
before these went in BELOW the Professor floor, so an insert bought review authority and nothing
else. These three are the first ever added above it, and they therefore clear, all at once and with
no line of code mentioning them:

  * BOTH HALVES OF THE REVIEW PAIR. ``can_review_record`` is "strictly below me" and
    ``can_edit_others_record`` is that same comparison narrowed to a Professor floor. INSPECTOR at 37
    cleared the first and not the second; 42 clears both. So a directorate tier may REWRITE a
    professor's record, not merely send it back. That is the owner's decision of 2026-09-13 and it is
    recorded here because nothing else in the repository would state it.
  * EVERY PROFESSOR FLOOR IN ``deps.py``: crafts, workshops, the questionnaire builder, dataset
    download, and ``require_professor``, which is the user table with promotion and demotion.
  * AND FIVE PROFESSOR FLOORS OUTSIDE ``deps.py``, which is the half nobody greps for and the half
    that carries regulated personal data: an artisan's unmasked Aadhaar number
    (``artisans._may_read_full_aadhaar``), de-masked identity numbers and every uploader's media URLs
    on every encoded record (``records.public_encode``), the same answer again on the transcript,
    annexure and export paths (``records.media_url_owners``), an empty DOWNLOAD filter
    (``records.owned_or_granted_where``), and APPROVED-on-create
    (``records.apply_status_policy_create``). Assertions 15-18 below are the only place in this
    repository that says anybody chose any of them.

AND THE ONE THING IT MUST NEVER BUY. ``deps.is_admin`` is SET MEMBERSHIP on ``{MASTER_ADMIN, ADMIN}``
and not a rank floor, so ``MINISTRY_ADMIN`` at 48 is NOT AN ADMIN — a token containing the word ADMIN
that passes no admin gate anywhere in this product. That is the most misreadable fact in the codebase
and it is asserted here rather than merely commented.

WHY THIS FILE IS NOT OPTIONAL, STATED PLAINLY. ``tests/test_review_edit_authority.py`` derives
``ALL_ROLES`` from ``deps.ROLE_RANK``, so its 8x8 = 64 pairs became 11x11 = 121 with no edit and it
now *asserts the new behaviour as correct* rather than flagging it. There is no failure to notice.
This file is the counterweight: it states the answer as a DECISION, so that narrowing it later is an
edit somebody makes on purpose with a reason beside it.

EVERY ASSERTION IS ON A PREDICATE AND ALMOST NOTHING TOUCHES A DATABASE — these are pure functions of
a role string. The two ``async`` cases call filter builders whose Professor arm returns before any
query, and the below-the-floor comparison is made with an id-less caller for the same reason.
"""

from types import SimpleNamespace
from typing import Any

import pytest
from fastapi import HTTPException

from app.api.routes import artisans, users
from app.core import deps
from app.services import design_workshop_inspectors, records

#: The three tiers, spelled once. Parametrised rather than triplicated because they landed in one
#: wave and share one argument; named as a constant so a rename is one edit and not sixty
#: silently-passing tests about roles nobody has.
DIRECTORATE = ("ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR", "MINISTRY_ADMIN")

#: What each tier's ``can_review_record`` reach is, written out as LITERALS rather than derived from
#: ``ROLE_RANK``. Deriving it would assert the arithmetic against itself and pass for any numbers at
#: all; these lists are the intention, and the derived set is checked against them in the same test.
REVIEW_REACH: dict[str, set[str]] = {
    "ASSISTANT_DIRECTOR": {
        "PROFESSOR",
        "INSPECTOR",
        "DESIGNER",
        "RESEARCHER",
        "FIELD_CONTRIBUTOR",
        "CROWDSOURCE_VOLUNTEER",
    },
    "REGIONAL_DIRECTOR": {
        "ASSISTANT_DIRECTOR",
        "PROFESSOR",
        "INSPECTOR",
        "DESIGNER",
        "RESEARCHER",
        "FIELD_CONTRIBUTOR",
        "CROWDSOURCE_VOLUNTEER",
    },
    "MINISTRY_ADMIN": {
        "REGIONAL_DIRECTOR",
        "ASSISTANT_DIRECTOR",
        "PROFESSOR",
        "INSPECTOR",
        "DESIGNER",
        "RESEARCHER",
        "FIELD_CONTRIBUTOR",
        "CROWDSOURCE_VOLUNTEER",
    },
}


def _user(role: str, user_id: str | None = "u1") -> SimpleNamespace:
    """A user row with every grantable capability explicitly OFF.

    The grants are explicit and false because several predicates here are ``rank OR grant``: with the
    flags absent, ``get_value`` answers ``None``, the assertions would still pass, and they would be
    passing for the wrong reason — a grant, not the rank. This file is about what the RANK carries.

    ``user_id=None`` is offered because two of the five outside-``deps.py`` floors fall through to a
    database read for callers BELOW the floor. An id-less caller short-circuits that read, which lets
    the below-the-floor half of those assertions be made without a connected Prisma client.
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
# 1-3. Where the tiers sit
# ────────────────────────────────────────────────────────────────────────────────────────────────


def test_the_three_tiers_sit_at_42_45_and_48_strictly_between_professor_and_admin() -> None:
    """The numbers, and the inequalities that give them their meaning.

    Asserted as literals AND as inequalities on purpose. The literals pin the values; the chain pins
    the INTENT, so a later renumbering that moves all three together still has to keep them above the
    professor they may rewrite and below the admin who may delete.
    """
    assert deps.ROLE_RANK["ASSISTANT_DIRECTOR"] == 42
    assert deps.ROLE_RANK["REGIONAL_DIRECTOR"] == 45
    assert deps.ROLE_RANK["MINISTRY_ADMIN"] == 48

    assert (
        deps.ROLE_RANK["PROFESSOR"]
        < deps.ROLE_RANK["ASSISTANT_DIRECTOR"]
        < deps.ROLE_RANK["REGIONAL_DIRECTOR"]
        < deps.ROLE_RANK["MINISTRY_ADMIN"]
        < deps.ROLE_RANK["ADMIN"]
    )

    # A GAP ON BOTH SIDES OF EVERY ONE OF THEM is why 42/45/48 was chosen out of the free 41-49 band
    # rather than 41/42/43. If one of these ever fails, somebody has filled a gap — fine — but they
    # should know they are spending the last one on that side.
    assert deps.ROLE_RANK["ASSISTANT_DIRECTOR"] - deps.ROLE_RANK["PROFESSOR"] > 1
    assert deps.ROLE_RANK["REGIONAL_DIRECTOR"] - deps.ROLE_RANK["ASSISTANT_DIRECTOR"] > 1
    assert deps.ROLE_RANK["MINISTRY_ADMIN"] - deps.ROLE_RANK["REGIONAL_DIRECTOR"] > 1
    assert deps.ROLE_RANK["ADMIN"] - deps.ROLE_RANK["MINISTRY_ADMIN"] > 1


def test_every_rank_on_the_ladder_is_distinct_and_that_is_not_a_style_preference() -> None:
    """Two tiers at one number would break a feature, silently, with no other test going red.

    ``tasks.assignable_or_refuse`` refuses when ``role_rank(assignee) >= role_rank(assigner)``, so
    two tiers sharing a number would be mutually unable to act on each other; and the web's
    ``ROLES_BY_RANK`` sorts on the VALUES, so a tie makes every role picker's order undefined between
    processes. If the product ever wants PEERS — regional directors of different regions — the ladder
    cannot express that and must not be bent into it: the precedent is a scope table, not a tier.
    """
    assert len(set(deps.ROLE_RANK.values())) == len(deps.ROLE_RANK)


@pytest.mark.parametrize(
    "tier,label",
    [
        ("ASSISTANT_DIRECTOR", "Assistant Director"),
        ("REGIONAL_DIRECTOR", "Regional Director"),
        ("MINISTRY_ADMIN", "Ministry Admin"),
    ],
)
def test_each_directorate_label_is_the_job_title_and_nothing_else(tier: str, label: str) -> None:
    """Plain title case, no slash, unlike INSPECTOR — there is no vocabulary collision to resolve.

    A missing label renders as the raw ``UPPER_SNAKE`` token inside an English sentence, on the
    screen where somebody decides who may sign a ministry report, so the closure is asserted too.
    """
    assert deps.ROLE_LABELS[tier] == label
    assert set(deps.ROLE_LABELS) == set(deps.ROLE_RANK)


# ────────────────────────────────────────────────────────────────────────────────────────────────
# 4-6. THE REVIEW AND EDIT PAIR — the decision this file was written for
# ────────────────────────────────────────────────────────────────────────────────────────────────


@pytest.mark.parametrize("tier", DIRECTORATE)
def test_each_tier_reviews_exactly_the_tiers_ranked_strictly_below_it(tier: str) -> None:
    """The reach, asserted twice: as a derived set and as literals.

    The derived half would pass for any numbers at all — it is the predicate compared with itself.
    The literal half is the intention, and it is what fails if a rank moves.
    """
    derived = {
        creator
        for creator in deps.ROLE_RANK
        if deps.ROLE_RANK[creator] < deps.ROLE_RANK[tier]
    }
    actual = {
        creator for creator in deps.ROLE_RANK if deps.can_review_record(_user(tier), creator)
    }
    assert actual == derived
    assert actual == REVIEW_REACH[tier], (
        f"{tier}'s review reach is no longer the set this change was signed off with. If a tier was "
        "inserted, add it to REVIEW_REACH deliberately; if a rank moved, this is the failure that "
        "was supposed to catch it."
    )


@pytest.mark.parametrize("tier", DIRECTORATE)
def test_the_edit_reach_equals_the_review_reach_which_is_where_these_tiers_differ_from_an_inspector(
    tier: str,
) -> None:
    """THE TIER WHERE THE TWO PREDICATES AGREE, and the agreement is the owner's decision.

    ``can_edit_others_record`` is ``has_rank(user, "PROFESSOR") and can_review_record(...)``. At 37
    an INSPECTOR satisfies the second and fails the first, so it may send a record back and may not
    rewrite it. At 42 and above both halves are satisfied, so these three may REWRITE what they may
    reject. The directorate corrects work rather than only returning it; that is why the ranks are
    above 40 and not below.
    """
    for creator in deps.ROLE_RANK:
        assert deps.can_edit_others_record(_user(tier), creator) is deps.can_review_record(
            _user(tier), creator
        ), f"{tier} may {'review' if deps.can_review_record(_user(tier), creator) else 'not review'} a {creator}'s record but the edit half disagrees"

    # The contrast, pinned in the same test so neither side can drift alone.
    assert deps.can_review_record(_user("INSPECTOR"), "DESIGNER") is True
    assert deps.can_edit_others_record(_user("INSPECTOR"), "DESIGNER") is False


@pytest.mark.parametrize("tier", DIRECTORATE)
def test_no_directorate_tier_reviews_a_peer_or_anybody_above_it(tier: str) -> None:
    """A tier that reviews everyone and is answerable to nobody would be a hole, not a rung.

    The comparison is STRICT on purpose, so a tier never reaches its own peers. ADMIN and
    MASTER_ADMIN outrank all three and are therefore out of reach in both halves of the pair.
    """
    above_or_equal = [
        role for role in deps.ROLE_RANK if deps.ROLE_RANK[role] >= deps.ROLE_RANK[tier]
    ]
    for role in above_or_equal:
        assert deps.can_review_record(_user(tier), role) is False, f"{tier} reviews {role}"
        assert deps.can_edit_others_record(_user(tier), role) is False, f"{tier} rewrites {role}"
    assert "ADMIN" in above_or_equal and "MASTER_ADMIN" in above_or_equal


# ────────────────────────────────────────────────────────────────────────────────────────────────
# 7. THE `is_admin` REFUSAL — the assertion this file exists for
# ────────────────────────────────────────────────────────────────────────────────────────────────


@pytest.mark.parametrize("tier", DIRECTORATE)
def test_a_token_containing_the_word_admin_still_passes_no_admin_gate(tier: str) -> None:
    """``deps.is_admin`` is SET MEMBERSHIP and must stay so.

    Widening it to a rank floor at 48 would grant record deletion, account creation and deletion,
    task assignment, workshop-access grants, viewer and inspector appointment, the usage aggregates,
    design-workshop export, the whole /admin route tree and the managed-API-key neighbourhood in ONE
    edit — which is precisely the objection ``can_read_usage``'s docstring makes about widening
    ``is_admin`` to answer a single question.
    """
    user = _user(tier)
    message = (
        f"a token containing the word ADMIN has acquired admin authority ({tier}); `deps.is_admin` "
        "is set membership and must stay so."
    )
    assert deps.is_admin(user) is False, message
    assert deps.is_master_admin(user) is False, message
    assert deps.can_create_design_workshops(user) is False, message
    assert deps.can_export_design_workshop_data(user) is False, message
    assert deps.can_manage_designer_roster(user) is False, message
    assert deps.can_manage_access_roster(user) is False, message
    assert deps.can_read_usage(user) is False, message
    assert deps.can_read_person_usage(user) is False, message
    assert deps.is_break_glass_master(user) is False, message


# ────────────────────────────────────────────────────────────────────────────────────────────────
# 8-9. What the Professor floor DOES buy, and what set membership still refuses
# ────────────────────────────────────────────────────────────────────────────────────────────────


@pytest.mark.parametrize("tier", DIRECTORATE)
def test_every_professor_floor_in_deps_opens_for_all_three_and_the_inheritance_is_intended(
    tier: str,
) -> None:
    """INHERITED, NOT GRANTED, and written down here because of that.

    None of these predicates names a tier. All of them are ``has_rank(user, "PROFESSOR")`` (two of
    them ``OR`` a grant, which is why ``_user`` sets every grant false), so all three cleared them the
    moment the numbers existed. The owner's constraint said rank >= 40 inherits craft-taxonomy
    management and edit-others-records; these are the rest of what came with it.
    """
    user = _user(tier)
    assert deps.can_manage_crafts(user) is True
    assert deps.can_manage_workshops(user) is True
    assert deps.can_manage_questionnaire(user) is True
    assert deps.can_download_dataset(user) is True
    assert deps.has_rank(user, "PROFESSOR") is True
    assert deps.can_access_review(user) is True
    assert deps.can_create_records(user) is True


@pytest.mark.parametrize("tier", DIRECTORATE)
def test_no_rank_buys_design_workshop_authority_because_every_one_of_those_gates_is_a_set(
    tier: str,
) -> None:
    """Exactly PROFESSOR's position, one band higher, and it must stay that way.

    Membership of ``DESIGN_WORKSHOP_ROLES`` is a WRITE grant, not a read grant:
    ``design_workshops.load_workshop_or_404(..., for_edit=True)`` performs no role check of its own.
    If ministry oversight needs workshop visibility, the precedent is INSPECTOR's read-only scoped
    grant table — a row an admin writes — and never a set entry.
    """
    user = _user(tier)
    assert deps.can_run_design_workshops(user) is False
    assert tier not in deps.DESIGN_WORKSHOP_ROLES
    assert tier not in deps.DESIGN_WORKSHOP_CREATOR_ROLES
    assert tier not in design_workshop_inspectors.INSPECTION_ROLES
    assert design_workshop_inspectors.is_inspector(user) is False


# ────────────────────────────────────────────────────────────────────────────────────────────────
# 10-11. The design-workshop DATA split, and the frozenset-vs-predicate trap
# ────────────────────────────────────────────────────────────────────────────────────────────────


@pytest.mark.parametrize("tier", DIRECTORATE)
def test_the_three_tiers_read_design_workshop_data_on_screen_and_cannot_export_it(tier: str) -> None:
    """THE 2026-09-13 RULING, ASSERTED AS ONE PAIR so neither half can move alone.

    ``DESIGN_WORKSHOP_DATA_VIEW_ROLES`` gained all three deliberately: leaving them out would make
    the ladder's own opening sentence — "higher rank inherits every power of the ranks below it" —
    false at rank 42, in a product whose ministry-facing outputs are the thing a directorate exists
    to read. It stayed a SET rather than becoming a Professor floor because the tier just BELOW the
    floor is INSPECTOR (37), who holds one workshop under a grant.

    ``DESIGN_WORKSHOP_DATA_EXPORT_ROLES`` did NOT move, which is the half that keeps this honest: a
    directorate account reads these rows on screen and cannot take them out of the product, exactly
    as a professor cannot. A screen is a reading; a file is a copy that leaves the building.
    """
    user = _user(tier)
    assert deps.can_view_design_workshop_data(user) is True
    assert deps.can_export_design_workshop_data(user) is False
    assert tier in deps.DESIGN_WORKSHOP_DATA_VIEW_ROLES
    assert tier not in deps.DESIGN_WORKSHOP_DATA_EXPORT_ROLES
    # The population the split exists for, and the tier just below the floor that the SET keeps out.
    assert deps.can_view_design_workshop_data(_user("PROFESSOR")) is True
    assert deps.can_view_design_workshop_data(_user("INSPECTOR")) is False


def test_the_documentation_frozensets_still_agree_with_the_predicates_that_ignore_them() -> None:
    """THE TRAP THAT NOTHING ELSE WATCHES, and the one assertion that would catch it.

    ``can_create_design_workshops`` and ``can_export_design_workshop_data`` both ``return
    is_admin(user)`` — neither reads the frozenset declared above it. On the WEB the arrays ARE the
    implementation (``permissions.ts``). So a well-meaning implementer who adds a tier to either
    server frozenset grants NOTHING server-side, grants the capability client-side, and ships a
    download button that answers 403 — with no test failing anywhere.
    """
    every_role = list(deps.ROLE_RANK)

    creators = {role for role in every_role if deps.can_create_design_workshops(_user(role))}
    assert set(deps.DESIGN_WORKSHOP_CREATOR_ROLES) == creators, (
        "this frozenset is DOCUMENTATION — the predicate is `is_admin` — and the web reads its own "
        "copy as the implementation. They have diverged; a button in the UI now 403s."
    )

    exporters = {role for role in every_role if deps.can_export_design_workshop_data(_user(role))}
    assert set(deps.DESIGN_WORKSHOP_DATA_EXPORT_ROLES) == exporters, (
        "this frozenset is DOCUMENTATION — the predicate is `is_admin` — and the web reads its own "
        "copy as the implementation. They have diverged; a button in the UI now 403s."
    )

    # The view set is the one that IS read by its predicate, asserted here so the contrast is on
    # the page: if this equality ever breaks, the cause is different from the two above.
    viewers = {role for role in every_role if deps.can_view_design_workshop_data(_user(role))}
    assert set(deps.DESIGN_WORKSHOP_DATA_VIEW_ROLES) == viewers


# ────────────────────────────────────────────────────────────────────────────────────────────────
# 12-14. The minting ceiling, the admin-only asserts, and the roster
# ────────────────────────────────────────────────────────────────────────────────────────────────


@pytest.mark.parametrize("tier", DIRECTORATE)
def test_each_tier_mints_at_or_below_itself_and_is_refused_everything_above(tier: str) -> None:
    """``users.assert_role``'s ceiling, which nothing tested before 2026-09-13.

    THE COMPARISON IS INCLUSIVE: the refusal fires on ``ROLE_RANK[role] > role_rank(current_user)``,
    so a tier may mint ITSELF. That is deliberate and it is worth pinning, because "at or below your
    own tier" and "strictly below your own tier" are one character apart in the source and a whole
    policy apart on the screen where somebody is promoted.
    """
    minter = _user(tier)
    for role in deps.ROLE_RANK:
        if deps.ROLE_RANK[role] <= deps.ROLE_RANK[tier]:
            users.assert_role(role, minter)  # must not raise
            continue
        with pytest.raises(HTTPException) as excinfo:
            users.assert_role(role, minter)
        assert excinfo.value.status_code == 403
        expected = (
            "Only the master admin can grant master admin"
            if role == "MASTER_ADMIN"
            else "You can only assign roles at or below your own tier"
        )
        assert excinfo.value.detail == expected, (
            f"{tier} was refused {role} with the wrong sentence. The two refusals mean different "
            "things and a caller distinguishes them by the string."
        )


@pytest.mark.parametrize("tier", DIRECTORATE)
def test_deleting_a_record_and_acting_on_somebody_elses_stay_admin_or_owner(tier: str) -> None:
    """The widening arrives through the REVIEW route and nowhere else.

    ``assert_can_delete`` is ``is_admin``; ``assert_owner_or_admin`` is ``is_admin`` OR ownership.
    Neither composes with rank, so no directorate tier reaches either on a record it does not own —
    which is the line between "corrects the archive" and "removes things from it".
    """
    user = _user(tier)
    somebody_elses: Any = SimpleNamespace(createdById="u-researcher", id="r1")

    with pytest.raises(HTTPException) as delete_refusal:
        deps.assert_can_delete(user)
    assert delete_refusal.value.status_code == 403

    with pytest.raises(HTTPException) as owner_refusal:
        deps.assert_owner_or_admin(user, somebody_elses)
    assert owner_refusal.value.status_code == 403


@pytest.mark.parametrize("tier", DIRECTORATE)
def test_no_directorate_tier_needs_a_designer_roster_row_and_none_can_suspend_it(tier: str) -> None:
    """``auth.assert_roster_admits`` gates accounts whose role is DESIGNER and no others.

    So the institution's record of a directorate officer's standing is the platform allow-list alone,
    exactly as it is for a professor and an admin. Written down because "a ministry admin ought to be
    on a roster" is a plausible product request, and it would be a NEW gate rather than a discovery
    about this one. Asserted through the rank rather than by importing ``auth``: the sign-in
    consequence is that these tiers are above DESIGNER, so ``auth``'s promote-to-designer arm
    (``role_rank < 35``) never touches them either.
    """
    assert deps.ROLE_RANK[tier] > deps.ROLE_RANK["DESIGNER"]


# ────────────────────────────────────────────────────────────────────────────────────────────────
# 15-18. THE FIVE PROFESSOR FLOORS OUTSIDE deps.py — the widest consequence of the change
# ────────────────────────────────────────────────────────────────────────────────────────────────


def test_a_directorate_tier_reads_an_unmasked_aadhaar_number_and_that_is_deliberate() -> None:
    """INHERITED, NOT GRANTED, AND WRITTEN DOWN HERE BECAUSE OF THAT.

    ``artisans._may_read_full_aadhaar`` is ``has_rank(user, "PROFESSOR")`` plus a creator carve-out,
    and its own docstring calls Aadhaar regulated personal data. Three tiers cleared that floor on
    2026-09-13 because of a number in ``ROLE_RANK``, with no line naming any of them and nothing
    going red. If the institution does NOT want a directorate account reading every artisan's full
    identity number, the fix is a NEW named predicate in ``artisans.py`` — never a rank change here,
    which would take the capability from professors and admins at the same time — and this test is
    where that decision gets recorded.
    """
    somebody_elses: Any = SimpleNamespace(createdById="u-researcher")
    for tier in DIRECTORATE:
        assert artisans._may_read_full_aadhaar(_user(tier), somebody_elses) is True, (
            f"{tier} no longer reads an unmasked Aadhaar number. If that narrowing was deliberate, "
            "change this assertion and say why; if it was not, a Professor floor moved and a "
            "professor lost it too."
        )
    # The floor itself, so the test fails loudly rather than silently if the shape changes.
    assert artisans._may_read_full_aadhaar(_user("INSPECTOR"), somebody_elses) is False


@pytest.mark.parametrize("tier", DIRECTORATE)
def test_a_directorate_tiers_own_fieldwork_enters_the_archive_approved(tier: str) -> None:
    """The one place a directorate account's own records arrive unreviewed.

    ``records.apply_status_policy_create`` is a bare Professor floor:
    professor-and-above keep whatever status they passed and default to APPROVED, everyone below is
    FORCED to PENDING. ``workshop_access.pin_pending_if_late`` still overrides it for a submission
    made after its workshop ended, and nothing else does.
    """
    assert records.apply_status_policy_create(_user(tier), {}) == {"status": "APPROVED"}
    assert records.apply_status_policy_create(_user("INSPECTOR"), {}) == {"status": "PENDING"}


async def test_a_directorate_tier_takes_every_row_out_and_reading_was_never_the_gate() -> None:
    """``owned_or_granted_where`` is the EXPORT filter and it is what the Professor floor moves.

    ``viewable_where`` is the READ filter and returns ``{}`` for every signed-in account at every
    rank — asserting it here would assert nothing, which is exactly the mistake an earlier review of
    this change made. The floor is in the OTHER function, and what it decides is not what may be read
    but what LEAVES.
    """
    for tier in DIRECTORATE:
        assert await records.owned_or_granted_where(_user(tier)) == {}, (
            f"{tier} no longer takes every row out in an export"
        )
    below = await records.owned_or_granted_where(_user("INSPECTOR"))
    assert below != {}, "the Professor floor on the export filter has gone"
    # And the read filter, asserted as the NON-gate it is, so nobody re-introduces the confusion.
    assert await records.viewable_where(_user("CROWDSOURCE_VOLUNTEER")) == {}


async def test_a_directorate_tier_resolves_every_uploaders_media_and_both_call_sites_matter() -> None:
    """``ALL_MEDIA_URLS`` is a SENTINEL, so this is asserted by identity and not by equality.

    The same Professor floor is spelled TWICE in ``services/records.py``: once inside
    ``public_encode``, which resolves media URLs on every encoded record, and once inside
    ``media_url_owners``, which the transcript, annexure and export paths read separately. A
    narrowing has to touch both or the two answers disagree about the same viewer.

    The below-the-floor half uses an id-less caller deliberately: for a caller under the floor this
    function reads ``DataAccessGrant``, and the point being made here is only that the sentinel is
    not handed out, which needs no database.
    """
    for tier in DIRECTORATE:
        assert await records.media_url_owners(_user(tier)) is records.ALL_MEDIA_URLS, (
            f"{tier} no longer resolves every uploader's media"
        )
    below = await records.media_url_owners(_user("INSPECTOR", user_id=None))
    assert below is not records.ALL_MEDIA_URLS, "the Professor floor on media URLs has gone"
