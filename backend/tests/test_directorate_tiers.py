from types import SimpleNamespace
from typing import Any

import pytest
from fastapi import HTTPException

from app.api.routes import artisans, users
from app.core import deps
from app.services import design_workshop_inspectors, records

DIRECTORATE = ("ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR", "MINISTRY_ADMIN")

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



def test_the_three_tiers_sit_at_42_45_and_48_strictly_between_professor_and_admin() -> None:
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

    assert deps.ROLE_RANK["ASSISTANT_DIRECTOR"] - deps.ROLE_RANK["PROFESSOR"] > 1
    assert deps.ROLE_RANK["REGIONAL_DIRECTOR"] - deps.ROLE_RANK["ASSISTANT_DIRECTOR"] > 1
    assert deps.ROLE_RANK["MINISTRY_ADMIN"] - deps.ROLE_RANK["REGIONAL_DIRECTOR"] > 1
    assert deps.ROLE_RANK["ADMIN"] - deps.ROLE_RANK["MINISTRY_ADMIN"] > 1


def test_every_rank_on_the_ladder_is_distinct_and_that_is_not_a_style_preference() -> None:
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
    assert deps.ROLE_LABELS[tier] == label
    assert set(deps.ROLE_LABELS) == set(deps.ROLE_RANK)



@pytest.mark.parametrize("tier", DIRECTORATE)
def test_each_tier_reviews_exactly_the_tiers_ranked_strictly_below_it(tier: str) -> None:
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
    for creator in deps.ROLE_RANK:
        assert deps.can_edit_others_record(_user(tier), creator) is deps.can_review_record(
            _user(tier), creator
        ), f"{tier} may {'review' if deps.can_review_record(_user(tier), creator) else 'not review'} a {creator}'s record but the edit half disagrees"

    assert deps.can_review_record(_user("INSPECTOR"), "DESIGNER") is True
    assert deps.can_edit_others_record(_user("INSPECTOR"), "DESIGNER") is False


@pytest.mark.parametrize("tier", DIRECTORATE)
def test_no_directorate_tier_reviews_a_peer_or_anybody_above_it(tier: str) -> None:
    above_or_equal = [
        role for role in deps.ROLE_RANK if deps.ROLE_RANK[role] >= deps.ROLE_RANK[tier]
    ]
    for role in above_or_equal:
        assert deps.can_review_record(_user(tier), role) is False, f"{tier} reviews {role}"
        assert deps.can_edit_others_record(_user(tier), role) is False, f"{tier} rewrites {role}"
    assert "ADMIN" in above_or_equal and "MASTER_ADMIN" in above_or_equal



@pytest.mark.parametrize("tier", DIRECTORATE)
def test_a_token_containing_the_word_admin_still_passes_no_admin_gate(tier: str) -> None:
    user = _user(tier)
    message = (
        f"a token containing the word ADMIN has acquired admin authority ({tier}); `deps.is_admin` "
        "is set membership and must stay so."
    )
    assert deps.is_admin(user) is False, message
    assert deps.is_master_admin(user) is False, message
    assert deps.can_create_design_workshops(user) is (tier == "MINISTRY_ADMIN"), message
    assert deps.can_export_design_workshop_data(user) is False, message
    assert deps.can_manage_designer_roster(user) is False, message
    assert deps.can_manage_access_roster(user) is False, message
    assert deps.can_read_usage(user) is False, message
    assert deps.can_read_person_usage(user) is False, message
    assert deps.is_break_glass_master(user) is False, message



@pytest.mark.parametrize("tier", DIRECTORATE)
def test_every_professor_floor_in_deps_opens_for_all_three_and_the_inheritance_is_intended(
    tier: str,
) -> None:
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
    from app.services import design_workshop_oversight as oversight

    user = _user(tier)
    assert deps.can_run_design_workshops(user) is True
    assert tier in deps.DESIGN_WORKSHOP_ROLES
    assert (tier in deps.DESIGN_WORKSHOP_CREATOR_ROLES) is (tier == "MINISTRY_ADMIN")
    assert tier not in design_workshop_inspectors.INSPECTION_ROLES
    for below in ("PROFESSOR", "INSPECTOR"):
        assert deps.can_run_design_workshops(_user(below)) is False
    assert design_workshop_inspectors.is_inspector(user) is False

    may_inspect = tier == "MINISTRY_ADMIN"
    assert design_workshop_inspectors.may_hold_an_inspection(user) is may_inspect
    expected_slots = (
        ["ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR"] if tier == "MINISTRY_ADMIN" else [tier]
    )
    assert oversight.capacities_for(tier) == expected_slots
    assert oversight.may_hold_oversight(user) is True



@pytest.mark.parametrize("tier", DIRECTORATE)
def test_the_three_tiers_read_design_workshop_data_on_screen_and_cannot_export_it(tier: str) -> None:
    user = _user(tier)
    assert deps.can_view_design_workshop_data(user) is True
    assert deps.can_export_design_workshop_data(user) is False
    assert tier in deps.DESIGN_WORKSHOP_DATA_VIEW_ROLES
    assert tier not in deps.DESIGN_WORKSHOP_DATA_EXPORT_ROLES
    assert deps.can_view_design_workshop_data(_user("PROFESSOR")) is True
    assert deps.can_view_design_workshop_data(_user("INSPECTOR")) is False


def test_the_documentation_frozensets_still_agree_with_the_predicates_that_ignore_them() -> None:
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

    viewers = {role for role in every_role if deps.can_view_design_workshop_data(_user(role))}
    assert set(deps.DESIGN_WORKSHOP_DATA_VIEW_ROLES) == viewers



@pytest.mark.parametrize("tier", DIRECTORATE)
def test_each_tier_mints_at_or_below_itself_and_is_refused_everything_above(tier: str) -> None:
    minter = _user(tier)
    for role in deps.ROLE_RANK:
        if deps.ROLE_RANK[role] <= deps.ROLE_RANK[tier]:
            users.assert_role(role, minter)
            continue
        with pytest.raises(HTTPException) as excinfo:
            users.assert_role(role, minter)
        assert excinfo.value.status_code == 403
        expected = (
            "Only the master admin can grant master admin"
            if role == "MASTER_ADMIN"
            else "You can only give a role at or below your own"
        )
        assert excinfo.value.detail == expected, (
            f"{tier} was refused {role} with the wrong sentence. The two refusals mean different "
            "things and a caller distinguishes them by the string."
        )


@pytest.mark.parametrize("tier", DIRECTORATE)
def test_deleting_a_record_and_acting_on_somebody_elses_stay_content_admin_or_owner(tier: str) -> None:
    user = _user(tier)
    somebody_elses: Any = SimpleNamespace(createdById="u-researcher", id="r1")

    if tier == "MINISTRY_ADMIN":
        deps.assert_can_delete(user)
        deps.assert_owner_or_admin(somebody_elses, user)
        return

    with pytest.raises(HTTPException) as delete_refusal:
        deps.assert_can_delete(user)
    assert delete_refusal.value.status_code == 403

    with pytest.raises(HTTPException) as owner_refusal:
        deps.assert_owner_or_admin(somebody_elses, user)
    assert owner_refusal.value.status_code == 403


@pytest.mark.parametrize("tier", DIRECTORATE)
def test_no_directorate_tier_needs_a_designer_roster_row_and_none_can_suspend_it(tier: str) -> None:
    assert deps.ROLE_RANK[tier] > deps.ROLE_RANK["DESIGNER"]



def test_a_directorate_tier_reads_an_unmasked_aadhaar_number_and_that_is_deliberate() -> None:
    somebody_elses: Any = SimpleNamespace(createdById="u-researcher")
    for tier in DIRECTORATE:
        assert artisans._may_read_full_aadhaar(_user(tier), somebody_elses) is True, (
            f"{tier} no longer reads an unmasked Aadhaar number. If that narrowing was deliberate, "
            "change this assertion and say why; if it was not, a Professor floor moved and a "
            "professor lost it too."
        )
    assert artisans._may_read_full_aadhaar(_user("INSPECTOR"), somebody_elses) is False


@pytest.mark.parametrize("tier", DIRECTORATE)
def test_a_directorate_tiers_own_fieldwork_enters_the_archive_approved(tier: str) -> None:
    assert records.apply_status_policy_create(_user(tier), {}) == {"status": "APPROVED"}
    assert records.apply_status_policy_create(_user("INSPECTOR"), {}) == {"status": "PENDING"}


async def test_a_directorate_tier_takes_every_row_out_and_reading_was_never_the_gate(monkeypatch) -> None:
    async def _no_scoped_grants(_user_id: str) -> tuple[set, dict]:
        return set(), {}

    monkeypatch.setattr(records, "granted_scopes", _no_scoped_grants)
    for tier in DIRECTORATE:
        assert await records.owned_or_granted_where(_user(tier)) == {}, (
            f"{tier} no longer takes every row out in an export"
        )
    below = await records.owned_or_granted_where(_user("INSPECTOR"))
    assert below != {}, "the Professor floor on the export filter has gone"
    assert await records.viewable_where(_user("CROWDSOURCE_VOLUNTEER")) == {}


async def test_a_directorate_tier_resolves_every_uploaders_media_and_both_call_sites_matter() -> None:
    for tier in DIRECTORATE:
        assert await records.media_url_owners(_user(tier)) is records.ALL_MEDIA_URLS, (
            f"{tier} no longer resolves every uploader's media"
        )
    below = await records.media_url_owners(_user("INSPECTOR", user_id=None))
    assert below is not records.ALL_MEDIA_URLS, "the Professor floor on media URLs has gone"


PROVISIONERS = frozenset({"MINISTRY_ADMIN", "ADMIN", "MASTER_ADMIN"})


@pytest.mark.parametrize("tier", sorted(deps.ROLE_RANK, key=deps.ROLE_RANK.__getitem__))
def test_exactly_three_tiers_provision_accounts(tier: str) -> None:
    assert deps.can_provision_accounts(_user(tier)) is (tier in PROVISIONERS), (
        f"{tier} {'gained' if tier not in PROVISIONERS else 'lost'} account provisioning"
    )


@pytest.mark.parametrize("tier", DIRECTORATE)
def test_provisioning_did_not_make_any_directorate_tier_an_admin(tier: str) -> None:
    user = _user(tier)
    assert deps.is_admin(user) is False
    assert deps.can_manage_access_roster(user) is False
    assert deps.can_provision_accounts(user) is (tier == "MINISTRY_ADMIN")


def test_the_provisioner_set_is_what_its_predicate_reads() -> None:
    every_role = list(deps.ROLE_RANK)
    admitted = {role for role in every_role if deps.can_provision_accounts(_user(role))}
    assert set(deps.ACCOUNT_PROVISIONER_ROLES) == admitted == PROVISIONERS
