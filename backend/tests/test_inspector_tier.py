from types import SimpleNamespace

import pytest

from app.core import deps

INSPECTOR = "INSPECTOR"


def _user(role: str, user_id: str = "u1") -> SimpleNamespace:
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



def test_the_tier_exists_at_37_strictly_between_designer_and_professor() -> None:
    assert deps.ROLE_RANK[INSPECTOR] == 37
    assert deps.ROLE_RANK["DESIGNER"] < deps.ROLE_RANK[INSPECTOR] < deps.ROLE_RANK["PROFESSOR"]

    assert deps.ROLE_RANK[INSPECTOR] - deps.ROLE_RANK["DESIGNER"] > 1
    assert deps.ROLE_RANK["PROFESSOR"] - deps.ROLE_RANK[INSPECTOR] > 1


def test_the_label_says_reviewer_too_and_the_stored_token_does_not() -> None:
    assert deps.ROLE_LABELS[INSPECTOR] == "Inspector / Reviewer"
    assert "Reviewer" in deps.ROLE_LABELS[INSPECTOR]
    assert "REVIEWER" not in deps.ROLE_RANK, (
        "a REVIEWER tier has appeared beside INSPECTOR. The token was deliberately not that word — "
        "`canReview` already owns 'review' in its relational sense. If the decision has genuinely "
        "been reversed, change it in one commit across the enum, the migration and every mirror; do "
        "not let the two spellings coexist."
    )
    assert set(deps.ROLE_LABELS) == set(deps.ROLE_RANK)



def test_an_inspector_may_review_a_designers_records() -> None:
    assert deps.can_review_record(_user(INSPECTOR), "DESIGNER") is True


@pytest.mark.parametrize(
    "creator_role", ["DESIGNER", "RESEARCHER", "FIELD_CONTRIBUTOR", "CROWDSOURCE_VOLUNTEER"]
)
def test_an_inspector_reviews_every_tier_beneath_it(creator_role: str) -> None:
    assert deps.can_review_record(_user(INSPECTOR), creator_role) is True


def test_an_inspector_may_not_review_a_peer_or_anyone_above() -> None:
    assert deps.can_review_record(_user(INSPECTOR), INSPECTOR) is False
    assert deps.can_review_record(_user(INSPECTOR), "PROFESSOR") is False
    assert deps.can_review_record(_user(INSPECTOR), "ADMIN") is False
    assert deps.can_review_record(_user(INSPECTOR), "MASTER_ADMIN") is False


def test_the_tiers_above_an_inspector_still_review_it() -> None:
    assert deps.can_review_record(_user("PROFESSOR"), INSPECTOR) is True
    assert deps.can_review_record(_user("ADMIN"), INSPECTOR) is True
    assert deps.can_review_record(_user("MASTER_ADMIN"), INSPECTOR) is True
    assert deps.can_review_record(_user("DESIGNER"), INSPECTOR) is False


def test_an_inspector_may_not_rewrite_what_it_may_review() -> None:
    for creator_role in ("DESIGNER", "RESEARCHER", "FIELD_CONTRIBUTOR", "CROWDSOURCE_VOLUNTEER"):
        assert deps.can_review_record(_user(INSPECTOR), creator_role) is True, creator_role
        assert deps.can_edit_others_record(_user(INSPECTOR), creator_role) is False, creator_role


def test_an_inspector_may_open_the_review_queue() -> None:
    assert deps.can_access_review(_user(INSPECTOR)) is True



def test_an_inspector_has_no_design_workshop_authority() -> None:
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
    assert getattr(deps, predicate)(_user(INSPECTOR)) is False


def test_an_inspector_is_not_an_admin() -> None:
    assert deps.is_admin(_user(INSPECTOR)) is False
    assert deps.is_master_admin(_user(INSPECTOR)) is False
    assert deps.has_rank(_user(INSPECTOR), "PROFESSOR") is False
    assert deps.has_rank(_user(INSPECTOR), "DESIGNER") is True


def test_an_inspector_may_create_records_because_the_ladder_is_inclusive() -> None:
    assert deps.can_create_records(_user(INSPECTOR)) is True
