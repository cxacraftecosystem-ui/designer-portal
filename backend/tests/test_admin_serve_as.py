import asyncio
import uuid
from types import SimpleNamespace
from typing import Any

import pytest
from conftest import needs_db
from fastapi import HTTPException

import app.services.stage_definitions  # noqa: F401  - installs the registry
from app.core import deps
from app.core.db import db
from app.core.security import create_access_token, hash_password
from app.services import access_roster, design_workshop_posts as posts

ME = "A. Sharma (a@x.test)"


def _refusals(post: str, *, held=(), authored=(), self_appointed=False) -> list[str]:
    return posts.separation_refusals(
        person=ME,
        post=post,
        standing=posts.Standing(posts=frozenset(held), authored=frozenset(authored)),
        self_appointed=self_appointed,
    )


def test_is_admin_is_still_exactly_the_two_platform_tiers():
    for role in deps.ROLE_RANK:
        assert deps.is_admin(SimpleNamespace(role=role)) is (role in {"ADMIN", "MASTER_ADMIN"}), role
    assert frozenset({"MINISTRY_ADMIN", "ADMIN", "MASTER_ADMIN"}) == posts.SERVING_ADMIN_ROLES


@pytest.mark.parametrize("post", ["ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR", "INSPECTOR", "DESIGNER"])
def test_a_clean_appointment_breaks_no_rule(post):
    assert _refusals(post) == []


@pytest.mark.parametrize("post", ["ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR", "INSPECTOR", "DESIGNER"])
def test_nobody_appoints_themselves_to_any_post(post):
    [refusal] = _refusals(post, self_appointed=True)
    assert "nobody appoints themselves" in refusal
    assert ME in refusal


def test_one_person_is_never_both_directors():
    for post, other in (("ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR"), ("REGIONAL_DIRECTOR", "ASSISTANT_DIRECTOR")):
        [refusal] = _refusals(post, held={post, other})
        assert "both the Assistant Director and the Regional Director" in refusal
    assert _refusals("ASSISTANT_DIRECTOR", held={"ASSISTANT_DIRECTOR"}) == []


def test_an_inspector_never_supervises_and_a_supervisor_never_inspects():
    [as_inspector] = _refusals("INSPECTOR", held={"REGIONAL_DIRECTOR"})
    assert "is this workshop's Regional Director, so they cannot also inspect it" in as_inspector
    [as_director] = _refusals("ASSISTANT_DIRECTOR", held={"INSPECTOR"})
    assert "inspects this workshop, so they cannot also be its Assistant Director" in as_director
    assert _refusals("INSPECTOR", held={"INSPECTOR"}) == []


@pytest.mark.parametrize("post", ["ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR", "INSPECTOR"])
@pytest.mark.parametrize(
    ("authored", "phrase"),
    [
        ({posts.DESIGNER_ACCESS}, "holds designer access to this workshop"),
        ({posts.STAGE_WRITES}, "has written this workshop's stages"),
        (
            {posts.DESIGNER_ACCESS, posts.STAGE_WRITES},
            "holds designer access to this workshop and has written its stages",
        ),
    ],
)
def test_an_author_never_inspects_or_supervises(post, authored, phrase):
    [refusal] = _refusals(post, authored=authored)
    assert phrase in refusal
    assert "nobody inspects or supervises work they authored" in refusal
    assert "Creating a workshop is not authoring it" in refusal


def test_authorship_is_no_bar_to_being_a_designer():
    assert _refusals("DESIGNER", authored={posts.DESIGNER_ACCESS, posts.STAGE_WRITES}) == []


@pytest.mark.parametrize("held", [{"INSPECTOR"}, {"ASSISTANT_DIRECTOR"}, {"REGIONAL_DIRECTOR"}])
def test_a_post_holder_is_refused_designer_access(held):
    [refusal] = _refusals("DESIGNER", held=held)
    assert "cannot also be given designer access to it" in refusal
    assert "does not write it" in refusal


def test_every_broken_rule_is_named_and_none_is_swallowed_by_another():
    refused = _refusals(
        "INSPECTOR",
        held={"ASSISTANT_DIRECTOR"},
        authored={posts.STAGE_WRITES},
        self_appointed=True,
    )
    assert len(refused) == 3


def test_the_write_refusal_names_every_post_in_one_order():
    assert posts.write_refusal({"INSPECTOR"}).startswith("You are this workshop's inspector, so you")
    assert posts.write_refusal({"INSPECTOR", "ASSISTANT_DIRECTOR"}).startswith(
        "You are this workshop's Assistant Director and inspector, so you"
    )
    assert posts.write_refusal({"REGIONAL_DIRECTOR"}) == (
        "You are this workshop's Regional Director, so you can read it but not change it: whoever "
        "inspects or supervises a workshop does not write it. Ask whoever made the appointment to "
        "take you off that post if you need to work on it."
    )


def test_an_account_problem_is_a_422_and_a_staffing_problem_alone_is_a_409():
    with pytest.raises(HTTPException) as staffing:
        posts.raise_refusals([], ["a", "a", "b"])
    assert staffing.value.status_code == 409
    assert staffing.value.detail == "a b Nothing was changed."

    with pytest.raises(HTTPException) as account:
        posts.raise_refusals(["x"], ["a"])
    assert account.value.status_code == 422
    assert account.value.detail == "x a Nothing was changed."

    posts.raise_refusals([], [])


@pytest.mark.parametrize("role", list(deps.ROLE_RANK))
def test_only_a_role_that_may_hold_a_post_pays_for_the_write_gates_query(role):
    expected = role in {"INSPECTOR", "ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR"} | set(
        posts.SERVING_ADMIN_ROLES
    )
    assert posts.could_hold_a_supervisory_post(SimpleNamespace(role=role)) is expected



def _account(role: str) -> SimpleNamespace:
    return SimpleNamespace(id=f"u-{role}", name=role.title(), email=f"{role.lower()}@x.test", role=role)


class _Users:
    def __init__(self) -> None:
        self.by_id = {f"u-{role}": _account(role) for role in deps.ROLE_RANK}

    async def find_many(self, where=None, **_kwargs):
        return [self.by_id[uid] for uid in where["id"]["in"] if uid in self.by_id]


@pytest.fixture
def staffed(monkeypatch: pytest.MonkeyPatch):
    state: dict[str, Any] = {"held": {}, "authored": {}}

    async def _held(workshop_id, user_ids):
        ids = set(user_ids)
        return {uid: frozenset(p) for uid, p in state["held"].items() if uid in ids}

    async def _authored(workshop_id, user_ids):
        ids = set(user_ids)
        return {uid: frozenset(e) for uid, e in state["authored"].items() if uid in ids}

    async def _bars_nobody(emails):
        return set()

    monkeypatch.setattr(posts, "supervisory_posts_among", _held)
    monkeypatch.setattr(posts, "authorship_among", _authored)
    monkeypatch.setattr(access_roster, "barred_among", _bars_nobody)
    return state


def _inspect(monkeypatch, ids, *, appointing=None):
    from app.services import design_workshop_inspectors as inspectors

    monkeypatch.setattr(inspectors, "db", SimpleNamespace(user=_Users()))
    try:
        asyncio.run(inspectors._assert_every_id_may_inspect("w1", set(ids), appointing=appointing))
    except HTTPException as refusal:
        return refusal
    return None


@pytest.mark.parametrize("role", ["INSPECTOR", "MINISTRY_ADMIN", "ADMIN", "MASTER_ADMIN"])
def test_the_inspector_tier_and_every_administrator_tier_may_be_appointed_to_inspect(
    staffed, monkeypatch, role
):
    assert _inspect(monkeypatch, {f"u-{role}"}) is None


@pytest.mark.parametrize("role", ["ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR", "PROFESSOR", "DESIGNER"])
def test_a_role_that_may_not_inspect_is_still_a_422(staffed, monkeypatch, role):
    refusal = _inspect(monkeypatch, {f"u-{role}"})
    assert refusal is not None and refusal.status_code == 422
    assert f"is a {role}" in refusal.detail


def test_the_inspector_validator_applies_every_separation_rule_as_a_409(staffed, monkeypatch):
    self_named = _inspect(monkeypatch, {"u-ADMIN"}, appointing="u-ADMIN")
    assert self_named is not None and self_named.status_code == 409
    assert "nobody appoints themselves" in self_named.detail

    staffed["held"] = {"u-MINISTRY_ADMIN": {"ASSISTANT_DIRECTOR"}}
    supervisor = _inspect(monkeypatch, {"u-MINISTRY_ADMIN"})
    assert supervisor is not None and supervisor.status_code == 409
    assert "cannot also inspect it" in supervisor.detail

    staffed["held"] = {}
    staffed["authored"] = {"u-MASTER_ADMIN": {posts.STAGE_WRITES}}
    author = _inspect(monkeypatch, {"u-MASTER_ADMIN"})
    assert author is not None and author.status_code == 409
    assert "has written this workshop's stages" in author.detail


def _grant(monkeypatch, ids, *, workshop_id="w1", appointing=None):
    from app.services import design_workshop_viewers as viewers

    async def _admits_every_designer(found):
        return {u.email for u in found}

    monkeypatch.setattr(viewers, "db", SimpleNamespace(user=_Users()))
    monkeypatch.setattr(viewers, "_designers_the_roster_still_admits", _admits_every_designer)
    try:
        asyncio.run(
            viewers._assert_every_id_may_be_granted(
                set(ids), workshop_id=workshop_id, appointing=appointing
            )
        )
    except HTTPException as refusal:
        return refusal
    return None


@pytest.mark.parametrize("post", ["INSPECTOR", "ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR"])
def test_the_workshops_inspector_or_supervisor_is_refused_a_viewer_row_on_it(staffed, monkeypatch, post):
    staffed["held"] = {"u-ADMIN": {post}}
    refusal = _grant(monkeypatch, {"u-ADMIN"})
    assert refusal is not None and refusal.status_code == 409
    assert "cannot also be given designer access" in refusal.detail
    staffed["held"] = {}
    assert _grant(monkeypatch, {"u-ADMIN"}) is None


def test_nobody_grants_themselves_designer_access(staffed, monkeypatch):
    refusal = _grant(monkeypatch, {"u-MINISTRY_ADMIN"}, appointing="u-MINISTRY_ADMIN")
    assert refusal is not None and refusal.status_code == 409
    assert "nobody appoints themselves" in refusal.detail


def test_without_a_workshop_the_grant_rule_asks_nothing_about_posts(staffed, monkeypatch):
    staffed["held"] = {"u-ADMIN": {"INSPECTOR"}}
    assert _grant(monkeypatch, {"u-ADMIN"}, workshop_id=None) is None


def test_a_role_refusal_and_a_post_refusal_arrive_together_as_one_422(staffed, monkeypatch):
    staffed["held"] = {"u-ADMIN": {"INSPECTOR"}}
    refusal = _grant(monkeypatch, {"u-ADMIN", "u-PROFESSOR"})
    assert refusal is not None and refusal.status_code == 422
    assert "PROFESSOR" in refusal.detail
    assert "cannot also be given designer access" in refusal.detail


def test_the_write_gate_refuses_a_holder_by_post_and_asks_nothing_of_a_designer(
    staffed, monkeypatch
):
    staffed["held"] = {"u-ADMIN": {"INSPECTOR"}, "u-MINISTRY_ADMIN": {"REGIONAL_DIRECTOR"}}
    for role, posts_held in (("ADMIN", {"INSPECTOR"}), ("MINISTRY_ADMIN", {"REGIONAL_DIRECTOR"})):
        with pytest.raises(HTTPException) as refused:
            asyncio.run(posts.refuse_a_holders_write("w1", _account(role)))
        assert refused.value.status_code == 403
        assert refused.value.detail == posts.write_refusal(posts_held)

    asyncio.run(posts.refuse_a_holders_write("w1", _account("MASTER_ADMIN")))

    async def _never(*_args, **_kwargs):
        raise AssertionError("a designer's stage save must not query who serves on the workshop")

    monkeypatch.setattr(posts, "supervisory_posts_among", _never)
    asyncio.run(posts.refuse_a_holders_write("w1", _account("DESIGNER")))


def test_the_artisan_import_asks_the_write_gate_before_it_reads_the_workbook():
    import inspect

    from app.api.routes import design_workshop_oversight as routes

    source = inspect.getsource(routes.upload_artisan_list)
    gate = source.find("posts.refuse_a_holders_write(workshop_id, current_user)")
    assert gate != -1, "the artisan import no longer asks whether its caller serves on the workshop"
    assert gate < source.find("run_artisan_import(")
    shared = inspect.getsource(routes.run_artisan_import)
    assert shared.find("_read_artisan_upload(file, request)") < shared.find("import_artisans(")


def _gate_comes_first(handler: Any, write: str) -> None:
    import inspect

    source = inspect.getsource(handler)
    gate = source.find("posts.refuse_a_holders_write(workshop_id, current_user)")
    assert gate != -1, f"{handler.__name__} no longer asks whether its caller serves on the workshop"
    assert source.find(write) != -1, f"{handler.__name__} no longer calls {write!r}"
    assert gate < source.find(write), f"{handler.__name__} writes before it asks the write gate"


def test_every_route_that_writes_the_team_or_unfiles_an_artisan_asks_the_write_gate_first():
    from app.api.routes import (
        design_workshop_oversight as oversight_routes,
        design_workshop_viewers as viewer_routes,
    )

    _gate_comes_first(oversight_routes.set_workshop_designer, "oversight.reassign_designer(")
    _gate_comes_first(oversight_routes.set_workshop_designers, "oversight.set_named_designers(")
    _gate_comes_first(
        oversight_routes.unlink_workshop_artisan, "oversight.unlink_artisan_from_workshop("
    )
    _gate_comes_first(viewer_routes.set_viewers, "replace_viewers(")


def test_the_doors_a_holder_keeps_do_not_ask_the_write_gate():
    import inspect

    from app.api.routes import (
        design_workshop_inspections as inspection_routes,
        design_workshop_oversight as oversight_routes,
        design_workshops as workshop_routes,
    )

    for handler in (
        oversight_routes.set_workshop_oversight,
        inspection_routes.set_inspectors,
        workshop_routes.restore_design_workshop,
        workshop_routes.generate_report,
    ):
        assert "refuse_a_holders_write" not in inspect.getsource(handler), handler.__name__
    assert "barred_to_post_holders=False" in inspect.getsource(workshop_routes.record_device_export)


class _Delegate:
    def __init__(self, row: Any = None) -> None:
        self.row = row
        self.written: list[Any] = []

    async def find_unique(self, where=None, **_kwargs):
        return self.row

    async def update(self, **kwargs):
        self.written.append(kwargs)

    async def create(self, **kwargs):
        self.written.append(kwargs)

    async def count(self, **_kwargs):
        return 0


def test_a_holder_decides_no_access_request_on_their_workshop_either_way(staffed, monkeypatch):
    from app.services import design_workshop_access as access

    row = SimpleNamespace(
        id="r1",
        designWorkshopId="w1",
        requestedById="u-DESIGNER",
        designWorkshop=SimpleNamespace(id="w1", createdById="u-opener", title="T", workshopCode=None),
        requestedBy=_account("DESIGNER"),
        status="PENDING",
    )
    requests = _Delegate(row)
    monkeypatch.setattr(access, "db", SimpleNamespace(designworkshopaccessrequest=requests))
    staffed["held"] = {"u-ADMIN": {"INSPECTOR"}}
    for decision in ("GRANTED", "DENIED"):
        with pytest.raises(HTTPException) as refused:
            asyncio.run(access.decide("r1", decision=decision, note=None, admin=_account("ADMIN")))
        assert refused.value.status_code == 403, decision
        assert refused.value.detail == posts.write_refusal({"INSPECTOR"})
    assert requests.written == [], "a refused decision wrote to the queue row"


def test_a_holder_prints_no_join_card_for_their_workshop(staffed, monkeypatch):
    from app.services import design_workshop_grants as grants

    workshop = SimpleNamespace(id="w1", createdById="u-opener", deletedAt=None)
    tokens = _Delegate()
    monkeypatch.setattr(
        grants,
        "db",
        SimpleNamespace(designworkshop=_Delegate(workshop), recordaccesstoken=tokens),
    )
    staffed["held"] = {"u-ADMIN": {"REGIONAL_DIRECTOR"}}
    with pytest.raises(HTTPException) as refused:
        asyncio.run(
            grants.mint_grant(
                _account("ADMIN"),
                record_type="DESIGN_WORKSHOP",
                record_id="w1",
                max_uses=5,
                days_valid=None,
                label=None,
            )
        )
    assert refused.value.status_code == 403
    assert refused.value.detail == posts.write_refusal({"REGIONAL_DIRECTOR"})
    assert tokens.written == [], "a card was minted for a refused issuer"


def test_the_write_loader_spares_a_holder_only_where_the_caller_says_so(staffed, monkeypatch):
    from app.services import design_workshops

    record = SimpleNamespace(id="w1", createdById="u-opener", deletedAt=None)
    monkeypatch.setattr(design_workshops, "db", SimpleNamespace(designworkshop=_Delegate(record)))
    staffed["held"] = {"u-ADMIN": {"INSPECTOR"}}
    admin = _account("ADMIN")

    with pytest.raises(HTTPException) as refused:
        asyncio.run(design_workshops.load_workshop_or_404("w1", admin, for_edit=True))
    assert refused.value.status_code == 403
    loaded = asyncio.run(
        design_workshops.load_workshop_or_404(
            "w1", admin, for_edit=True, barred_to_post_holders=False
        )
    )
    assert loaded is record
    assert asyncio.run(design_workshops.load_workshop_or_404("w1", admin)) is record


def test_the_designer_directories_leave_the_caller_out_only_for_a_workshop_that_exists(
    monkeypatch,
):
    from app.api.routes import (
        design_workshop_oversight as oversight_routes,
        design_workshop_viewers as viewer_routes,
    )

    asked: list[tuple[str, str | None]] = []

    async def _viewers(search=None, *, exclude_user_id=None):
        asked.append(("eligible-viewers", exclude_user_id))
        return {"users": [], "truncated": False}

    async def _designers(*, search=None, include_suspended=False, exclude_user_id=None, **_kw):
        asked.append(("designers", exclude_user_id))
        return []

    monkeypatch.setattr(viewer_routes, "eligible_viewers", _viewers)
    monkeypatch.setattr(oversight_routes, "workshop_capable_accounts", _designers)
    reader = _account("ADMIN")
    for workshop_id in (None, "", "w1"):
        asyncio.run(
            viewer_routes.list_eligible_viewers(
                search=None, workshopId=workshop_id, current_user=reader
            )
        )
        asyncio.run(
            oversight_routes.list_assignable_designers(
                search=None, workshopId=workshop_id, current_user=reader
            )
        )
    assert asked == [
        ("eligible-viewers", None),
        ("designers", None),
        ("eligible-viewers", None),
        ("designers", None),
        ("eligible-viewers", "u-ADMIN"),
        ("designers", "u-ADMIN"),
    ]


def test_the_directory_queries_leave_the_caller_out_inside_the_where(monkeypatch):
    from app.services import design_workshop_viewers as viewers, designers

    sent: list[dict] = []

    class _Users:
        async def find_many(self, where=None, **_kwargs):
            sent.append(where)
            return []

    async def _roster():
        return [], False

    async def _bars_nobody():
        return []

    monkeypatch.setattr(viewers, "active_roster_emails", _roster)
    monkeypatch.setattr(access_roster, "barred_emails", _bars_nobody)
    monkeypatch.setattr(viewers, "db", SimpleNamespace(user=_Users()))
    monkeypatch.setattr(designers, "db", SimpleNamespace(user=_Users()))

    asyncio.run(viewers.eligible_viewers(exclude_user_id="u-me"))
    asyncio.run(designers.workshop_capable_accounts(exclude_user_id="u-me"))
    asyncio.run(viewers.eligible_viewers())
    asyncio.run(designers.workshop_capable_accounts())
    excluded = [{"id": {"not": "u-me"}} in where["AND"] for where in sent]
    assert excluded == [True, True, False, False]



def _serving(monkeypatch, on: dict[str, dict[str, set[str]]]) -> None:
    async def _held(workshop_id, user_ids):
        ids = set(user_ids)
        return {uid: frozenset(p) for uid, p in on.get(workshop_id, {}).items() if uid in ids}

    monkeypatch.setattr(posts, "supervisory_posts_among", _held)


def _file(monkeypatch, data: dict[str, Any], user: Any, *, filed_under: str | None):
    from app.services import design_workshops, record_design_workshop as filing

    asked: list[str] = []

    async def _destination(workshop_id, _user, **_kwargs):
        asked.append(workshop_id)

    async def _never_deleted(_workshop_id):
        return None

    monkeypatch.setattr(design_workshops, "load_workshop_or_404", _destination)
    monkeypatch.setattr(filing, "refuse_if_the_workshop_is_deleted", _never_deleted)
    try:
        asyncio.run(filing.assert_payload_workshop(data, user, filed_under=filed_under))
    except HTTPException as refusal:
        return refusal, asked
    return None, asked


@pytest.mark.parametrize(
    ("role", "post"),
    [
        ("INSPECTOR", "INSPECTOR"),
        ("ASSISTANT_DIRECTOR", "ASSISTANT_DIRECTOR"),
        ("REGIONAL_DIRECTOR", "REGIONAL_DIRECTOR"),
        ("MINISTRY_ADMIN", "REGIONAL_DIRECTOR"),
        ("ADMIN", "INSPECTOR"),
        ("MASTER_ADMIN", "ASSISTANT_DIRECTOR"),
    ],
)
def test_a_record_form_refuses_a_holder_taking_a_record_out_of_their_workshop(
    monkeypatch, role, post
):
    _serving(monkeypatch, {"w1": {f"u-{role}": {post}}})
    holder = _account(role)
    for body in ({"designWorkshopId": None}, {"designWorkshopId": ""}, {"designWorkshopId": "w2"}):
        refusal, asked = _file(monkeypatch, body, holder, filed_under="w1")
        assert refusal is not None and refusal.status_code == 403, body
        assert refusal.detail == posts.write_refusal({post}), body
        assert asked == [], f"{body}: the destination was asked before the leave was refused"


def test_a_holder_changes_nothing_on_a_record_filed_under_their_workshop(monkeypatch):
    _serving(monkeypatch, {"w1": {"u-ADMIN": {"INSPECTOR"}}})
    holder = _account("ADMIN")
    for body in ({"notes": "Re-measured."}, {"designWorkshopId": "w1"}, {}):
        refusal, asked = _file(monkeypatch, body, holder, filed_under="w1")
        assert refusal is not None and refusal.status_code == 403, body
        assert refusal.detail == posts.write_refusal({"INSPECTOR"}), body
        assert asked == [], body


def test_off_their_workshop_a_holders_record_forms_are_answered_exactly_as_before(monkeypatch):
    _serving(monkeypatch, {"w1": {"u-ADMIN": {"INSPECTOR"}}})
    holder = _account("ADMIN")
    assert _file(monkeypatch, {"notes": "Re-measured."}, holder, filed_under="w3") == (None, [])
    assert _file(monkeypatch, {"designWorkshopId": "w3"}, holder, filed_under="w3") == (None, [])
    assert _file(monkeypatch, {"designWorkshopId": None}, holder, filed_under=None) == (None, [])
    assert _file(monkeypatch, {"designWorkshopId": "w2"}, holder, filed_under=None) == (None, ["w2"])
    assert _file(monkeypatch, {"designWorkshopId": None}, holder, filed_under="w3") == (None, [])
    assert _file(monkeypatch, {"designWorkshopId": "w2"}, holder, filed_under="w3") == (None, ["w2"])


def test_everybody_else_takes_a_record_out_and_a_designer_is_asked_nothing(monkeypatch):
    _serving(monkeypatch, {"w1": {"u-ADMIN": {"INSPECTOR"}}})
    for role in ("MASTER_ADMIN", "MINISTRY_ADMIN", "PROFESSOR", "RESEARCHER"):
        user = _account(role)
        assert _file(monkeypatch, {"notes": "Re-measured."}, user, filed_under="w1") == (None, [])
        assert _file(monkeypatch, {"designWorkshopId": None}, user, filed_under="w1") == (None, [])
        assert _file(monkeypatch, {"designWorkshopId": "w2"}, user, filed_under="w1") == (
            None,
            ["w2"],
        )

    async def _never(*_args, **_kwargs):
        raise AssertionError("a designer's save must not query who serves on the workshop")

    monkeypatch.setattr(posts, "supervisory_posts_among", _never)
    designer = _account("DESIGNER")
    assert _file(monkeypatch, {"designWorkshopId": None}, designer, filed_under="w1") == (None, [])
    assert _file(monkeypatch, {"notes": "Re-measured."}, designer, filed_under="w1") == (None, [])


def test_the_questionnaire_form_patch_refuses_a_holder_everything_and_writes_nothing(monkeypatch):
    from app.api.routes import questionnaire_forms as forms
    from app.schemas.questionnaire import QuestionnaireUpdate

    record = SimpleNamespace(id="q1", ownerId="u-opener", designWorkshopId="w1")
    table = _Delegate(record)
    attached_to: list[str] = []

    async def _found(_questionnaire_id, _user):
        return record

    async def _attachable(workshop_id, _user):
        attached_to.append(workshop_id)

    async def _form(_questionnaire_id, **_kwargs):
        return {"id": "q1"}

    monkeypatch.setattr(forms, "_require_questionnaire", _found)
    monkeypatch.setattr(forms, "_require_attachable_workshop", _attachable)
    monkeypatch.setattr(forms, "load_form", _form)
    monkeypatch.setattr(forms, "db", SimpleNamespace(questionnaire=table))

    async def _no_reconcile(*_args, **_kwargs):
        return 0

    monkeypatch.setattr(forms.cross_portal_codes, "reconcile_form_question_codes", _no_reconcile)
    _serving(monkeypatch, {"w1": {"u-ADMIN": {"REGIONAL_DIRECTOR"}}})

    for body in (
        {"designWorkshopId": None},
        {"designWorkshopId": "w2"},
        {"designWorkshopId": "w1"},
        {"title": "Renamed by its director"},
        {"isActive": False},
    ):
        with pytest.raises(HTTPException) as refused:
            asyncio.run(
                forms.update_questionnaire("q1", QuestionnaireUpdate(**body), _account("ADMIN"))
            )
        assert refused.value.status_code == 403, body
        assert refused.value.detail == posts.write_refusal({"REGIONAL_DIRECTOR"})
    assert table.written == [], "a refused edit wrote to the questionnaire"
    assert attached_to == [], "the destination was asked before the holder was refused"

    asyncio.run(
        forms.update_questionnaire(
            "q1", QuestionnaireUpdate(designWorkshopId="w1"), _account("MASTER_ADMIN")
        )
    )
    assert attached_to == ["w1"]
    asyncio.run(
        forms.update_questionnaire(
            "q1", QuestionnaireUpdate(designWorkshopId=None), _account("MASTER_ADMIN")
        )
    )
    assert table.written[-1] == {"where": {"id": "q1"}, "data": {"designWorkshopId": None}}


_EARLY_WRITES = ("attach_location(", "resolve_craft_id(")


def _asks_before(source: str, gate: str, *writes: str, name: str) -> None:
    at = source.find(gate)
    assert at != -1, f"{name} no longer asks {gate!r}"
    for write in writes:
        if write in source:
            assert at < source.find(write), f"{name} reaches {write!r} before it asks {gate!r}"


def test_every_record_form_passes_the_workshop_its_row_is_filed_under():
    import inspect

    from app.api.routes import artisans, processes, products, questionnaire, tools

    for handler, row in (
        (artisans.update_artisan, "artisan"),
        (products.update_product, "product"),
        (processes.update_process, "process"),
        (tools.update_tool, "tool"),
        (questionnaire.update_interview, "interview"),
    ):
        _asks_before(
            inspect.getsource(handler),
            f"assert_payload_workshop(data, current_user, filed_under={row}.designWorkshopId)",
            *_EARLY_WRITES,
            "async with db.tx()",
            name=handler.__name__,
        )
    for handler in (
        artisans.create_artisan,
        products.create_product,
        processes.create_process,
        tools.create_tool,
    ):
        source = inspect.getsource(handler)
        for gate in (
            "assert_payload_workshop(data, current_user, filed_under=None)",
            "enforce_workshop_submission(",
        ):
            _asks_before(source, gate, *_EARLY_WRITES, name=handler.__name__)


def test_every_other_write_to_a_filed_record_asks_about_the_workshop_it_is_filed_under():
    import inspect

    from app.api.routes import artisans, processes, products, questionnaire, tools

    for handler, row, write in (
        (artisans.delete_artisan, "artisan", "db.artisan.delete("),
        (products.delete_product, "product", "db.productdocumentation.delete("),
        (processes.delete_process, "process", "db.process.delete("),
        (tools.delete_tool, "tool", "db.tooldocumentation.delete("),
        (questionnaire.delete_interview, "interview", "db.questionnaireinterview.delete("),
        (tools.assign_tool_artisans, "tool", "db.toolartisan.create_many("),
        (tools.unassign_tool_artisan, "tool", "db.toolartisan.delete_many("),
    ):
        _asks_before(
            inspect.getsource(handler),
            f"assert_may_write_a_record_filed_under({row}.designWorkshopId, current_user)",
            write,
            name=handler.__name__,
        )

    source = inspect.getsource(questionnaire.merge_interview_into)
    _asks_before(
        source,
        "await assert_may_write_a_record_filed_under(side, current_user)",
        "_merge_scope(source) != _merge_scope(target)",
        "async with db.tx()",
        name="merge_interview_into",
    )
    assert "source.designWorkshopId" in source and "target.designWorkshopId" in source


def test_every_edit_of_a_questionnaire_form_asks_about_the_workshop_it_is_attached_to():
    import inspect

    from app.api.routes import questionnaire_forms as forms

    gate = "_refuse_its_workshops_holder(record, current_user)"
    for handler, write in (
        (forms.reupload_questionnaire, "_read_upload(file, request)"),
        (forms.update_questionnaire, "_require_attachable_workshop("),
        (forms.update_questionnaire, "db.questionnaire.update("),
        (forms.create_section, "db.questionnaireformsection.create("),
        (forms.update_section, "db.questionnaireformsection.update("),
        (forms.create_question, "db.questionnaireformquestion.create("),
        (forms.update_question, "supersede_question("),
        (forms.update_question, "db.questionnaireformquestion.update("),
        (forms.remove_question, "db.questionnaireformquestion.update("),
        (forms.remove_question, "db.questionnaireformquestion.delete("),
        (forms.update_entry, "db.questionnaireformentry.update("),
    ):
        _asks_before(inspect.getsource(handler), gate, write, name=handler.__name__)
    for handler in (forms.create_entry, forms.record_answers):
        assert "_require_recordable_questionnaire(record, current_user)" in (
            inspect.getsource(handler)
        ), handler.__name__


@pytest.mark.parametrize("kind", ["artisan", "product", "tool"])
def test_a_refused_create_mints_no_location_and_no_craft(monkeypatch, kind):
    from app.api.routes import artisans, products, tools

    module, handler = {
        "artisan": (artisans, artisans.create_artisan),
        "product": (products, products.create_product),
        "tool": (tools, tools.create_tool),
    }[kind]
    reached: list[str] = []

    def _writer(name: str):
        async def _write(data, *_args, **_kwargs):
            reached.append(name)
            return data

        return _write

    async def _refused(_data, _user, *, filed_under):
        raise HTTPException(status_code=403, detail=posts.write_refusal({"INSPECTOR"}))

    async def _any_workshop(_user, _workshop_id, **_kwargs):
        return SimpleNamespace()

    monkeypatch.setattr(module, "assert_payload_workshop", _refused)
    monkeypatch.setattr(module, "enforce_workshop_submission", _any_workshop)
    monkeypatch.setattr(module, "attach_location", _writer("attach_location"))
    if hasattr(module, "resolve_craft_id"):
        monkeypatch.setattr(module, "resolve_craft_id", _writer("resolve_craft_id"))
    body = {
        "designWorkshopId": "w1",
        "craftName": "A craft the register lacks",
        "location": {"latitude": 21.19, "longitude": 83.58},
    }
    payload = SimpleNamespace(
        model_dump=lambda **_kwargs: dict(body), clientKey=None, duplicateAcknowledged=None
    )

    with pytest.raises(HTTPException) as refused:
        asyncio.run(handler(payload, current_user=_account("ADMIN")))
    assert refused.value.status_code == 403
    assert reached == [], f"a refused create reached {reached}"



class _Table:
    def __init__(self, *rows: Any) -> None:
        self.rows = list(rows)

    async def find_unique(self, where=None, **_kwargs):
        return next((row for row in self.rows if row.id == where["id"]), None)

    async def find_many(self, where=None, **_kwargs):
        return [
            row
            for row in self.rows
            if all(getattr(row, key, None) == value for key, value in (where or {}).items())
        ]


class _MediaWorld:
    def __init__(self, *, staged: dict[str, set[str]] | None = None, **tables: Any) -> None:
        self.staged = staged or {}
        self.asked: list[tuple[str, list[str], list[str]]] = []
        for name in (
            "artisan",
            "productdocumentation",
            "tooldocumentation",
            "questionnaireinterview",
            "process",
            "processstep",
            "mediafile",
            "dwailayer",
        ):
            setattr(self, name, tables.get(name, _Table()))

    async def query_raw(self, _sql, media_id, entity_keys, field_keys):
        self.asked.append((media_id, entity_keys, field_keys))
        return [{"id": workshop_id} for workshop_id in sorted(self.staged.get(media_id, set()))]


def _file_row(media_id: str = "m1", **columns: Any) -> SimpleNamespace:
    blank = dict.fromkeys(
        (
            "designWorkshopId",
            "linkedRecordType",
            "linkedRecordId",
            "artisanId",
            "productId",
            "toolId",
            "questionnaireInterviewId",
        )
    )
    return SimpleNamespace(id=media_id, **{**blank, **columns})


def test_a_file_belongs_to_every_workshop_that_names_it_by_any_of_five_ways(monkeypatch):
    from app.services.stage_schema import FieldType, stages

    world = _MediaWorld(
        staged={"m1": {"w-stage"}},
        artisan=_Table(SimpleNamespace(id="a1", designWorkshopId="w-artisan")),
        dwailayer=_Table(
            SimpleNamespace(sourceMediaId="m1", designWorkshopId="w-ai", deletedAt=None),
            SimpleNamespace(sourceMediaId="m1", designWorkshopId="w-gone", deletedAt="2026-10-01"),
        ),
    )
    monkeypatch.setattr(posts, "db", world)
    media = _file_row(
        designWorkshopId="w-filed",
        linkedRecordType="designWorkshop",
        linkedRecordId="w-tag",
        artisanId="a1",
    )
    found = asyncio.run(posts.media_design_workshop_ids(media))
    assert found == {"w-filed", "w-tag", "w-stage", "w-ai", "w-artisan"}

    [(media_id, entity_keys, field_keys)] = world.asked
    assert media_id == "m1"
    wanted = {
        f.key
        for spec in stages()
        for entity in spec.entities
        for f in entity.fields
        if f.type.is_media or f.type is FieldType.RICH_TEXT
    }
    assert set(field_keys) == wanted, "the stage scan does not read every field that holds a file"
    assert entity_keys, "the stage scan was narrowed to no entity at all"


def test_the_record_a_file_hangs_off_is_read_through_every_spelling_and_one_hop(monkeypatch):
    world = _MediaWorld(
        process=_Table(SimpleNamespace(id="p1", designWorkshopId="w-process")),
        processstep=_Table(SimpleNamespace(id="s1", processId="p1")),
        questionnaireinterview=_Table(SimpleNamespace(id="i-new", designWorkshopId="w-sitting")),
        mediafile=_Table(
            _file_row(
                "m-parent",
                designWorkshopId="w-parent",
                linkedRecordType="designworkshop",
                linkedRecordId="w-parent-tag",
            )
        ),
    )
    monkeypatch.setattr(posts, "db", world)
    assert asyncio.run(posts.link_filing("processstep", "s1")) == {"w-process"}
    assert asyncio.run(posts.link_filing("misc", "m-parent")) == {"w-parent", "w-parent-tag"}
    assert asyncio.run(posts.link_filing("questionnaireinterview", "i-new")) == {"w-sitting"}
    assert asyncio.run(posts.link_filing("questionnaire", "i-gone")) == set()
    assert asyncio.run(posts.link_filing("craft", "c1")) == set()
    assert asyncio.run(posts.link_filing(None, "p1")) == set()

    merged = _file_row(
        questionnaireInterviewId="i-new", linkedRecordType="questionnaire", linkedRecordId="i-gone"
    )
    assert asyncio.run(posts.media_design_workshop_ids(merged)) == {"w-sitting"}


def test_a_holder_changes_no_file_of_their_workshop_and_everybody_else_is_answered_as_before(
    monkeypatch,
):
    _serving(monkeypatch, {"w1": {"u-ADMIN": {"INSPECTOR"}}})
    belongs: dict[str, set[str]] = {"m-held": {"w1", "w9"}, "m-free": {"w9"}}

    async def _workshops_of(media):
        return set(belongs[media.id])

    async def _filed(link_type, record_id):
        return {"w1"} if (link_type, record_id) == ("artisan", "a-held") else set()

    monkeypatch.setattr(posts, "media_design_workshop_ids", _workshops_of)
    monkeypatch.setattr(posts, "link_filing", _filed)
    holder, other = _account("ADMIN"), _account("MASTER_ADMIN")

    for media, relinked_to in (
        (_file_row("m-held"), None),
        (_file_row("m-free"), ("artisan", "a-held")),
    ):
        with pytest.raises(HTTPException) as refused:
            asyncio.run(posts.refuse_a_holders_media_write(media, holder, relinked_to=relinked_to))
        assert refused.value.status_code == 403
        assert refused.value.detail == posts.write_refusal({"INSPECTOR"})
        asyncio.run(posts.refuse_a_holders_media_write(media, other, relinked_to=relinked_to))
    asyncio.run(posts.refuse_a_holders_media_write(_file_row("m-free"), holder))
    asyncio.run(
        posts.refuse_a_holders_media_write(
            _file_row("m-free"), holder, relinked_to=("artisan", "a-loose")
        )
    )

    async def _never(*_args, **_kwargs):
        raise AssertionError("a designer's media write must not read which workshops hold the file")

    monkeypatch.setattr(posts, "media_design_workshop_ids", _never)
    asyncio.run(posts.refuse_a_holders_media_write(_file_row("m-held"), _account("DESIGNER")))


def test_every_media_door_asks_about_the_files_workshops_before_it_writes():
    import inspect

    from app.api.routes import design_workshops as workshop_routes, media as media_routes

    gate = "design_workshop_posts.refuse_a_holders_media_write("
    for handler, writes in (
        (media_routes.delete_media, ("db.mediafile.delete(", "delete_object")),
        (media_routes.set_media_transcript, ("db.mediafile.update(",)),
        (media_routes.refine_media_transcript, ("transcription_verdict(", "refine_transcript_text(")),
        (media_routes.transcribe_media_now_route, ("transcribe_media_now(media",)),
        (media_routes.relink_media, ("db.mediafile.update(",)),
        (
            workshop_routes.decide_identity_photograph,
            ("db.mediafile.update(", "delete_object", "db.mediafile.delete("),
        ),
    ):
        source = inspect.getsource(handler)
        _asks_before(source, gate, *writes, name=handler.__name__)
        asked = source[source.find(gate) + len(gate) :].lstrip()
        assert asked.startswith("media, current_user"), f"{handler.__name__} asks about another file"
    assert "relinked_to=(rec_type, payload.linkedRecordId)" in inspect.getsource(
        media_routes.relink_media
    )


def test_the_upload_the_job_retry_and_the_review_edit_ask_before_they_write():
    import inspect

    from app.api.routes import media as media_routes, review as review_routes

    gate = "design_workshop_posts.refuse_a_holders_media_write("

    def asked_about(source: str) -> str:
        return source[source.find(gate) + len(gate) :].lstrip()

    complete = inspect.getsource(media_routes.complete_media_upload)
    _asks_before(
        complete,
        gate,
        "_assert_stored_object_within_ceiling(",
        "attach_location(",
        "db.mediafile.create(",
        name="complete_media_upload",
    )
    assert asked_about(complete).startswith("_upload_as_filed(payload), current_user")
    assert complete.find("_finish_pending_media(existing") < complete.find(gate), (
        "the replay of the caller's own upload must be answered as it was"
    )

    retry = inspect.getsource(media_routes.retry_media_processing_job)
    _asks_before(
        retry, gate, "db.mediaprocessingjob.update_many(", name="retry_media_processing_job"
    )
    assert asked_about(retry).startswith("media, current_user")

    edit = inspect.getsource(review_routes.edit_reviewed_record)
    for review_gate in (gate, "assert_may_write_a_record_filed_under("):
        _asks_before(
            edit, review_gate, "async with db.tx()", "record_revision(", name="edit_reviewed_record"
        )
    assert asked_about(edit).startswith("record, reviewer")
    assert "designWorkshopId" in review_routes._NOT_REVIEW_EDITABLE  # noqa: SLF001


def test_an_upload_is_read_as_the_row_it_would_become(monkeypatch):
    from app.api.routes import media as media_routes
    from app.schemas.media import MediaCompleteRequest

    world = _MediaWorld(artisan=_Table(SimpleNamespace(id="a1", designWorkshopId="w-artisan")))
    monkeypatch.setattr(posts, "db", world)

    def upload(**links: Any) -> Any:
        return media_routes._upload_as_filed(  # noqa: SLF001
            MediaCompleteRequest(
                originalFilename="loom.jpg",
                mediaType="IMAGE",
                mimeType="image/jpeg",
                sizeBytes=1024,
                objectKey="media/u1/loom.jpg",
                **links,
            )
        )

    assert asyncio.run(
        posts.media_design_workshop_ids(
            upload(linkedRecordType="designWorkshop", linkedRecordId="w-tag")
        )
    ) == {"w-tag"}
    assert asyncio.run(
        posts.media_design_workshop_ids(upload(linkedRecordType="artisan", linkedRecordId="a1"))
    ) == {"w-artisan"}
    assert asyncio.run(
        posts.media_design_workshop_ids(upload(designWorkshopId="w-filed"))
    ) == {"w-filed"}
    assert asyncio.run(posts.media_design_workshop_ids(upload())) == set()
    assert world.asked == [], "a row that does not exist yet was looked for in the stages"



def test_the_ladder_reads_no_row_a_design_workshop_claims():
    from app.services import workshop_inference as inference

    unfiled = {"workshopId": None, "designWorkshopId": None}
    assert unfiled == inference._UNFILED_RECORD  # noqa: SLF001
    media = inference._UNFILED_MEDIA  # noqa: SLF001
    assert {key: media[key] for key in unfiled} == unfiled
    assert {"linkedRecordType": None} in media["OR"], "a file with no tag at all would be dropped"
    assert {
        "NOT": {"linkedRecordType": {"equals": "designWorkshop", "mode": "insensitive"}}
    } in media["OR"]

    import inspect

    ladder = "".join(inspect.getsource(inference.run_ladder).split())
    for table in (
        "productdocumentation",
        "tooldocumentation",
        "process",
        "artisan",
    ):
        assert f"db.{table}.find_many(where=_UNFILED_RECORD" in ladder, table
    assert 'db.questionnaireinterview.find_many(where={**_UNFILED_RECORD,"deletedAt":None}' in ladder
    assert "db.mediafile.find_many(where=_UNFILED_MEDIA" in ladder


def test_the_unfiled_doors_ask_about_the_design_workshop_before_they_write():
    import inspect

    from app.api.routes import workshops as workshop_routes
    from app.services import workshop_inference as inference

    for handler in (
        workshop_routes.map_unmapped_records,
        workshop_routes.file_one_unmapped_record,
        workshop_routes.discard_one_unmapped_record,
    ):
        source = inspect.getsource(handler)
        assert "current_user: Any = Depends(require_content_admin)" in source, handler.__name__
        assert "user=current_user)" in source, f"{handler.__name__} drops the caller"

    filed = inspect.getsource(inference.file_one_unmapped)
    _asks_before(filed, "_refuse_a_holder(", "db.workshop.find_unique(", "update_many(", name="file")
    discarded = inspect.getsource(inference.discard_one_unmapped)
    for gate in ("_refuse_a_holder(", "if claimed:"):
        _asks_before(
            discarded, gate, "_media_kept_by(", "delete_many(", "delete_object", name="discard"
        )
    mapped = inspect.getsource(inference.apply_workshop_mapping)
    _asks_before(mapped, "_rows_held_from(", "update_many(", name="apply_workshop_mapping")


def test_nobody_takes_themselves_off_a_post_and_the_sentence_says_who_can():
    assert posts.self_release_refusal({"INSPECTOR"}) == (
        "You are this workshop's inspector, and nobody takes themselves off a post: another "
        "administrator has to take you off. Nothing was changed."
    )
    assert posts.self_release_refusal({"REGIONAL_DIRECTOR", "ASSISTANT_DIRECTOR"}).startswith(
        "You are this workshop's Assistant Director and Regional Director, and nobody"
    )


PASSWORD = "admin-serve-as-password"

INTRO_STAGE = "INTRODUCTORY_ADMIN_DOCUMENTATION"
INTRO_ENTITY = "introduction"

ACCOUNTS: tuple[tuple[str, str, str], ...] = (
    ("master", "MASTER_ADMIN", "Appointing Master Admin"),
    ("opener", "ADMIN", "Opening Admin"),
    ("ministry", "MINISTRY_ADMIN", "Serving Ministry Admin"),
    ("admin", "ADMIN", "Serving Admin"),
    ("master2", "MASTER_ADMIN", "Serving Master Admin"),
    ("ministryOpener", "MINISTRY_ADMIN", "Opening Ministry Admin"),
    ("ad", "ASSISTANT_DIRECTOR", "Assistant Director Tier"),
    ("rd", "REGIONAL_DIRECTOR", "Regional Director Tier"),
    ("inspector", "INSPECTOR", "Inspector Tier"),
    ("designer", "DESIGNER", "Rostered Designer"),
)

RECORD_PATHS: dict[str, str] = {
    "artisan": "/api/artisans",
    "product": "/api/products",
    "process": "/api/processes",
    "tool": "/api/tools",
    "interview": "/api/questionnaire/interviews",
    "questionnaire": "/api/questionnaires",
}


async def _seed_filed(
    kind: str, *, author: str, workshop_id: str | None, label: str, parent: str
) -> str:
    filed = {"designWorkshopId": workshop_id} if workshop_id else {}
    if kind == "questionnaire":
        form = await db.questionnaire.create(
            data={"title": f"Form {label}", "ownerId": author, **filed}
        )
        return form.id
    table, columns = {
        "artisan": (db.artisan, {"name": f"Artisan {label}", "place": "Barpali"}),
        "product": (
            db.productdocumentation,
            {
                "craftName": "Sambalpuri Ikat",
                "place": "Barpali",
                "artisanName": f"Artisan {label}",
                "productName": f"Ikat sari {label}",
            },
        ),
        "process": (db.process, {"name": f"Tie and dye {label}", "productId": parent}),
        "tool": (
            db.tooldocumentation,
            {
                "craftName": "Sambalpuri Ikat",
                "place": "Barpali",
                "artisanName": f"Artisan {label}",
                "toolkitName": f"Pit loom {label}",
            },
        ),
        "interview": (db.questionnaireinterview, {"title": f"Sitting {label}"}),
    }[kind]
    row = await table.create(data={**columns, "createdById": author, **filed})
    return row.id


def _first_field_of(kind: Any) -> tuple[str, str, str]:
    from app.services.stage_schema import stages

    for spec in stages():
        for entity in spec.entities:
            for field in entity.fields:
                if field.type is kind:
                    return spec.key, entity.key, field.key
    raise AssertionError(f"the registry has no {kind} field")


async def _seed_media(people: dict[str, Any], tag: str) -> dict[str, str]:
    from app.services.stage_schema import FieldType
    from prisma import Json

    author = people["master"].id
    held = await db.designworkshop.create(
        data={"title": f"Files while serving {tag}", "createdById": people["opener"].id}
    )
    free = await db.designworkshop.create(
        data={"title": f"Files nobody serves on {tag}", "createdById": people["opener"].id}
    )
    pictured = await db.artisan.create(
        data={
            "name": f"Pictured artisan {tag}",
            "place": "Barpali",
            "createdById": author,
            "designWorkshopId": held.id,
        }
    )
    unfiled = await db.artisan.create(
        data={"name": f"Unfiled artisan {tag}", "place": "Barpali", "createdById": author}
    )

    async def upload(label: str, *, kind: str = "IMAGE", by: str = author, **links: Any) -> str:
        row = await db.mediafile.create(
            data={
                "originalFilename": f"{label}.{'jpg' if kind == 'IMAGE' else 'm4a'}",
                "mediaType": kind,
                "mimeType": "image/jpeg" if kind == "IMAGE" else "audio/mp4",
                "sizeBytes": 2048,
                "bucket": "test-bucket",
                "objectKey": f"media/{by}/serve-as-{tag}-{label}",
                "uploadedById": by,
                **links,
            }
        )
        return row.id

    def tagged(workshop: Any) -> dict[str, str]:
        return {"linkedRecordType": "designWorkshop", "linkedRecordId": workshop.id}

    transcript = {"transcriptText": "As the artisan said it.", "transcriptStatus": "COMPLETED"}
    files = {
        "tagged": await upload("tagged", **tagged(held)),
        "staged": await upload("staged"),
        "rich": await upload("rich"),
        "filed": await upload("filed", kind="AUDIO", designWorkshopId=held.id, **transcript),
        "onRecord": await upload(
            "on-record", linkedRecordType="artisan", linkedRecordId=pictured.id, artisanId=pictured.id
        ),
        "ministryOwn": await upload("ministry-own", by=people["ministry"].id, **tagged(held)),
        "freeTagged": await upload("free-tagged", **tagged(free)),
        "freeMoved": await upload("free-moved", **tagged(free)),
        "freeAudio": await upload("free-audio", kind="AUDIO", designWorkshopId=free.id, **transcript),
        "freeKept": await upload("free-kept", **tagged(free)),
        "freeDeleted": await upload("free-deleted", **tagged(free)),
    }
    stage, entity, field = _first_field_of(FieldType.IMAGE)
    await db.dwstageentry.create(
        data={
            "designWorkshopId": held.id,
            "stageKey": stage,
            "entityKey": entity,
            "ordinal": 0,
            "data": Json({field: files["staged"]}),
        }
    )
    stage, entity, field = _first_field_of(FieldType.RICH_TEXT)
    placed = {"blocks": [{"kind": "IMAGE", "media": files["rich"], "spans": ["The seam."]}]}
    await db.dwstageentry.create(
        data={
            "designWorkshopId": held.id,
            "stageKey": stage,
            "entityKey": entity,
            "ordinal": 0,
            "data": Json({field: placed}),
        }
    )
    return {"held": held.id, "free": free.id, "pictured": pictured.id, "unfiled": unfiled.id, **files}


REVIEWED: dict[str, tuple[str, dict[str, str]]] = {
    "artisan": ("artisan", {"notes": "Corrected from the review queue."}),
    "product": ("product", {"remarks": "Corrected from the review queue."}),
    "process": ("process", {"notes": "Corrected from the review queue."}),
    "tool": ("tool", {"remarks": "Corrected from the review queue."}),
    "questionnaire": ("interview", {"notes": "Corrected from the review queue."}),
    "media": ("media", {"caption": "Corrected from the review queue."}),
}


async def _seed_the_later_doors(people: dict[str, Any], tag: str, *, parent: str) -> dict[str, Any]:
    author = people["designer"].id
    doors: dict[str, Any] = {}
    for place in ("held", "free"):
        workshop = await db.designworkshop.create(
            data={"title": f"The later doors, {place} {tag}", "createdById": people["opener"].id}
        )
        doors[place] = workshop.id
    for review_type, (kind, _fields) in REVIEWED.items():
        at: dict[str, str] = {}
        for place in ("held", "free"):
            if kind == "media":
                row = await db.mediafile.create(
                    data={
                        "originalFilename": f"reviewed-{place}.jpg",
                        "mediaType": "IMAGE",
                        "mimeType": "image/jpeg",
                        "sizeBytes": 2048,
                        "bucket": "test-bucket",
                        "objectKey": f"media/{author}/serve-as-{tag}-reviewed-{place}",
                        "uploadedById": author,
                        "designWorkshopId": doors[place],
                    }
                )
                at[place] = row.id
            else:
                at[place] = await _seed_filed(
                    kind,
                    author=author,
                    workshop_id=doors[place],
                    label=f"reviewed {review_type} {place} {tag}",
                    parent=parent,
                )
        doors[review_type] = at
    jobs: dict[str, str] = {}
    for place in ("held", "free"):
        recording = await db.mediafile.create(
            data={
                "originalFilename": f"retried-{place}.m4a",
                "mediaType": "AUDIO",
                "mimeType": "audio/mp4",
                "sizeBytes": 2048,
                "bucket": "test-bucket",
                "objectKey": f"media/{author}/serve-as-{tag}-retried-{place}",
                "uploadedById": author,
                "designWorkshopId": doors[place],
            }
        )
        job = await db.mediaprocessingjob.create(
            data={
                "jobType": "TRANSCRIPTION",
                "status": "FAILED",
                "mediaFileId": recording.id,
                "requestedById": author,
                "attempts": 3,
                "maxAttempts": 3,
                "error": "The provider was unreachable.",
            }
        )
        jobs[place] = job.id
    doors["jobs"] = jobs
    return doors


async def _seed_the_unfiled(people: dict[str, Any], tag: str, *, media: dict[str, str]) -> dict:
    from datetime import UTC, datetime

    from app.services import workshop_inference
    from app.services.stage_schema import FieldType
    from prisma import Json

    author = people["master"].id
    held = await db.designworkshop.create(
        data={"title": f"Unfiled while serving {tag}", "createdById": people["opener"].id}
    )
    crafts = await db.workshop.create(
        data={
            "title": f"Crafts workshop for the unfiled {tag}",
            "date": datetime(2026, 3, 2, tzinfo=UTC),
            "place": "Barpali",
            "createdById": author,
        }
    )
    ids: dict[str, Any] = {"held": held.id, "heldTitle": held.title, "crafts": crafts.id}

    async def record(kind: str, name: str, *, filed: bool) -> str:
        return await _seed_filed(
            kind,
            author=author,
            workshop_id=held.id if filed else None,
            label=f"unfiled {name} {tag}",
            parent="",
        )

    ids["filedArtisan"] = await record("artisan", "filedArtisan", filed=True)
    ids["looseArtisan"] = await record("artisan", "looseArtisan", filed=False)
    for name, filed in (
        ("filedProduct", True),
        ("mapRecordHeld", True),
        ("parentHeld", True),
        ("looseProduct", False),
        ("looseProductHolder", False),
        ("looseDiscard", False),
        ("mapProduct", False),
    ):
        ids[name] = await record("product", name, filed=filed)

    async def upload(label: str, **links: Any) -> str:
        row = await db.mediafile.create(
            data={
                "originalFilename": f"{label}.jpg",
                "mediaType": "IMAGE",
                "mimeType": "image/jpeg",
                "sizeBytes": 2048,
                "bucket": "test-bucket",
                "objectKey": f"media/{author}/serve-as-{tag}-unfiled-{label}",
                "uploadedById": author,
                **links,
            }
        )
        return row.id

    files = {
        "tagged": await upload("tagged", linkedRecordType="designWorkshop", linkedRecordId=held.id),
        "taggedLower": await upload(
            "tagged-lower", linkedRecordType="designworkshop", linkedRecordId=held.id
        ),
        "filed": await upload("filed", designWorkshopId=held.id),
        "staged": await upload("staged"),
        "onRecord": await upload(
            "on-record",
            linkedRecordType="artisan",
            linkedRecordId=ids["filedArtisan"],
            artisanId=ids["filedArtisan"],
        ),
        "otherTag": await upload(
            "other-tag", linkedRecordType="artisan", linkedRecordId=ids["looseArtisan"]
        ),
        "loose": await upload("loose"),
        "looseHolder": await upload("loose-holder"),
        "mapHeld": await upload(
            "map-held",
            linkedRecordType="product",
            linkedRecordId=ids["parentHeld"],
            productId=ids["parentHeld"],
        ),
        "mapFree": await upload("map-free"),
    }
    ids.update(files)
    stage, entity, field = _first_field_of(FieldType.IMAGE)
    await db.dwstageentry.create(
        data={
            "designWorkshopId": held.id,
            "stageKey": stage,
            "entityKey": entity,
            "ordinal": 0,
            "data": Json({field: files["staged"]}),
        }
    )

    run = await workshop_inference.run_ladder()
    listed = {row.id for plan in run.plans.values() for row in plan.rows}
    ids["listed"] = {
        name: ids[name] in listed
        for name in (
            *files,
            "filedArtisan",
            "looseArtisan",
            "filedProduct",
            "looseProduct",
        )
    }

    seeded = {*files.values(), *(media[key] for key in media if key not in _SEED_MEDIA_PLACES)}
    rows = await db.mediafile.find_many(where={"id": {"in": sorted(seeded)}})
    held_set = {held.id, media["held"]}
    by_file = {row.id: await posts.media_design_workshop_ids(row) for row in rows}
    ids["parity"] = {
        "fileByFile": sorted(mid for mid, found in by_file.items() if found & held_set),
        "batched": sorted(await posts.media_held_among(rows, held_set)),
        "asked": len(rows),
    }
    return ids


_SEED_MEDIA_PLACES = frozenset({"held", "free", "pictured", "unfiled"})


@pytest.fixture(scope="module")
def world():
    from fastapi.testclient import TestClient

    from app.main import app

    tag = uuid.uuid4().hex[:8]

    def address(slug: str) -> str:
        return f"serve-as-{slug.lower()}-{tag}@example.org"

    async def seed() -> dict[str, Any]:
        await db.connect()
        try:
            people: dict[str, Any] = {}
            for slug, role, name in ACCOUNTS:
                people[slug] = await db.user.create(
                    data={
                        "email": address(slug),
                        "name": f"{name} {tag}",
                        "role": role,
                        "passwordHash": hash_password(PASSWORD),
                    }
                )
            await db.designerroster.create(
                data={
                    "email": address("designer"),
                    "fullName": "Rostered Designer",
                    "institution": "Directorate of Handicrafts",
                    "isActive": True,
                    "addedById": people["master"].id,
                }
            )
            await db.designerprofile.create(
                data={
                    "user": {"connect": {"id": people["opener"].id}},
                    "displayName": f"Opening Admin {tag}",
                    "biography": "Twelve years of administration, none of it fieldwork.",
                }
            )
            asked = await db.designworkshop.create(
                data={"title": f"Asked to join {tag}", "createdById": people["opener"].id}
            )
            request = await db.designworkshopaccessrequest.create(
                data={
                    "designWorkshopId": asked.id,
                    "requestedById": people["designer"].id,
                    "source": "MANUAL",
                }
            )
            author = people["master"].id
            craft = await db.craft.create(data={"name": f"Serve-as ikat {tag}"})
            parent = await db.productdocumentation.create(
                data={
                    "craftName": "Sambalpuri Ikat",
                    "place": "Barpali",
                    "artisanName": f"Parent artisan {tag}",
                    "productName": f"Parent sari {tag}",
                    "createdById": author,
                }
            )
            filing: dict[str, dict[str, dict[str, str]]] = {}
            for kind in RECORD_PATHS:
                for purpose in ("refused", "kept", "edited"):
                    ids: dict[str, str] = {}
                    for place in ("held", "free"):
                        workshop = await db.designworkshop.create(
                            data={
                                "title": f"Files a {kind}, {purpose}, {place} {tag}",
                                "createdById": people["opener"].id,
                            }
                        )
                        ids[place] = workshop.id
                    placed = [("atHeld", ids["held"]), ("atFree", ids["free"]), ("loose", None)]
                    if kind == "interview" and purpose == "edited":
                        placed += [
                            ("mergeHeld", ids["held"]),
                            ("mergeFree", ids["free"]),
                            ("mergeFreeToo", ids["free"]),
                        ]
                    for name, workshop_id in placed:
                        ids[name] = await _seed_filed(
                            kind,
                            author=author,
                            workshop_id=workshop_id,
                            label=f"{purpose} {name} {tag}",
                            parent=parent.id,
                        )
                    filing.setdefault(kind, {})[purpose] = ids
            media = await _seed_media(people, tag)
            return {
                "people": people,
                "tag": tag,
                "askedWorkshop": asked.id,
                "accessRequest": request.id,
                "craft": craft.id,
                "parentProduct": parent.id,
                "filing": filing,
                "media": media,
                "doors": await _seed_the_later_doors(people, tag, parent=parent.id),
                "unfiled": await _seed_the_unfiled(people, tag, media=media),
            }
        finally:
            await db.disconnect()

    seeded = asyncio.run(seed())
    with TestClient(app) as client:
        seeded["client"] = client
        yield seeded


def _headers(world: dict[str, Any], slug: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(world['people'][slug].id)}"}


def _id(world: dict[str, Any], slug: str | None) -> str | None:
    return world["people"][slug].id if slug else None


def _open(world: dict[str, Any], title: str) -> str:
    response = world["client"].post(
        "/api/design-workshops",
        json={
            "title": f"{title} {world['tag']}",
            "craftName": "Sambalpuri Ikat",
            "clusterName": "Barpali",
        },
        headers=_headers(world, "opener"),
    )
    assert response.status_code == 201, response.text
    return response.json()["id"]


def _officers(world, workshop_id, *, as_slug="master", **slots):
    body: dict[str, Any] = {}
    if "ad" in slots:
        body["assistantDirectorId"] = _id(world, slots["ad"])
    if "rd" in slots:
        body["regionalDirectorId"] = _id(world, slots["rd"])
    return world["client"].put(
        f"/api/design-workshop-oversight/{workshop_id}",
        json=body,
        headers=_headers(world, as_slug),
    )


def _inspectors(world, workshop_id, slugs, *, as_slug="master"):
    return world["client"].put(
        f"/api/design-workshop-inspections/{workshop_id}/inspectors",
        json={"userIds": [_id(world, s) for s in slugs]},
        headers=_headers(world, as_slug),
    )


def _designers(world, workshop_id, slugs, *, as_slug="master"):
    return world["client"].put(
        f"/api/design-workshop-oversight/{workshop_id}/designers",
        json={"userIds": [_id(world, s) for s in slugs]},
        headers=_headers(world, as_slug),
    )


def _viewers(world, workshop_id, slugs, *, as_slug="master"):
    return world["client"].put(
        f"/api/design-workshops/{workshop_id}/viewers",
        json={"userIds": [_id(world, s) for s in slugs]},
        headers=_headers(world, as_slug),
    )


def _write(world, workshop_id, slug, text="Revive the Barpali ikat motifs for a new market."):
    return world["client"].put(
        f"/api/design-workshops/{workshop_id}/stages/{INTRO_STAGE}",
        json={
            "entries": [{"entityKey": INTRO_ENTITY, "data": {"purpose": text}, "merge": True}],
            "replaceCollections": False,
        },
        headers=_headers(world, slug),
    )


def _listed(world, path, slug) -> list[str]:
    response = world["client"].get(path, headers=_headers(world, slug))
    assert response.status_code == 200, response.text
    return [item["id"] for item in response.json()["items"]]


@needs_db
def test_every_administrator_tier_serves_in_every_supervisory_post(world):
    rotation = (
        ("ministry", "admin", "master2"),
        ("admin", "master2", "ministry"),
        ("master2", "ministry", "admin"),
    )
    for n, (ad, rd, inspector) in enumerate(rotation):
        workshop_id = _open(world, f"Rotation {n}")
        appointed = _officers(world, workshop_id, ad=ad, rd=rd)
        assert appointed.status_code == 200, appointed.text
        assert {row["capacity"]: row["userId"] for row in appointed.json()["oversight"]} == {
            "ASSISTANT_DIRECTOR": _id(world, ad),
            "REGIONAL_DIRECTOR": _id(world, rd),
        }
        assigned = _inspectors(world, workshop_id, [inspector])
        assert assigned.status_code == 200, assigned.text

        for officer in (ad, rd):
            assert workshop_id in _listed(world, "/api/design-workshop-oversight/assigned", officer)
            read = world["client"].get(
                f"/api/design-workshop-oversight/assigned/{workshop_id}",
                headers=_headers(world, officer),
            )
            assert read.status_code == 200, read.text
            assert read.json()["readOnly"] is True
        assert workshop_id in _listed(world, "/api/design-workshop-inspections", inspector)
        read = world["client"].get(
            f"/api/design-workshop-inspections/{workshop_id}", headers=_headers(world, inspector)
        )
        assert read.status_code == 200, read.text
        assert read.json()["readOnly"] is True


@needs_db
def test_every_administrator_tier_is_named_a_designer_and_then_writes(world):
    workshop_id = _open(world, "Team of administrators")
    before = _write(world, workshop_id, "ministry")
    assert before.status_code == 200, "a content admin is a privileged editor of every workshop"

    named = _designers(world, workshop_id, ["ministry", "admin", "master2"])
    assert named.status_code == 200, named.text
    assert {row["userId"] for row in named.json()["designers"]} == {
        _id(world, s) for s in ("ministry", "admin", "master2")
    }
    for slug in ("ministry", "admin", "master2"):
        assert _write(world, workshop_id, slug).status_code == 200, slug


@needs_db
def test_nobody_appoints_themselves_to_any_of_the_four_posts(world):
    workshop_id = _open(world, "Self appointment")
    for response in (
        _officers(world, workshop_id, ad="master"),
        _inspectors(world, workshop_id, ["master"]),
        _designers(world, workshop_id, ["master"]),
        _viewers(world, workshop_id, ["master"]),
    ):
        assert response.status_code == 409, response.text
        assert "nobody appoints themselves" in response.json()["detail"]

    staffing = world["client"].get(
        f"/api/design-workshop-oversight/{workshop_id}", headers=_headers(world, "master")
    ).json()
    assert staffing["oversight"] == [] and staffing["designers"] == [], "a refusal wrote anyway"
    panel = world["client"].get(
        f"/api/design-workshop-inspections/{workshop_id}/inspectors",
        headers=_headers(world, "master"),
    ).json()
    assert panel["inspectors"] == []


@needs_db
def test_one_person_never_holds_both_director_posts_on_one_workshop(world):
    workshop_id = _open(world, "Both directors")
    both = _officers(world, workshop_id, ad="ministry", rd="ministry")
    assert both.status_code == 409, both.text
    assert "both the Assistant Director and the Regional Director" in both.json()["detail"]

    assert _officers(world, workshop_id, ad="ministry").status_code == 200
    later = _officers(world, workshop_id, rd="ministry")
    assert later.status_code == 409, "the second slot was taken in a second save"

    swapped = _officers(world, workshop_id, ad="admin", rd="ministry")
    assert swapped.status_code == 200, swapped.text
    assert {row["capacity"]: row["userId"] for row in swapped.json()["oversight"]} == {
        "ASSISTANT_DIRECTOR": _id(world, "admin"),
        "REGIONAL_DIRECTOR": _id(world, "ministry"),
    }


@needs_db
def test_an_inspector_never_supervises_the_workshop_and_a_supervisor_never_inspects_it(world):
    workshop_id = _open(world, "Inspector and supervisor")
    assert _inspectors(world, workshop_id, ["admin"]).status_code == 200

    as_director = _officers(world, workshop_id, rd="admin")
    assert as_director.status_code == 409, as_director.text
    assert "inspects this workshop" in as_director.json()["detail"]

    assert _officers(world, workshop_id, ad="ministry").status_code == 200
    as_inspector = _inspectors(world, workshop_id, ["admin", "ministry"])
    assert as_inspector.status_code == 409, as_inspector.text
    assert "is this workshop's Assistant Director, so they cannot also inspect it" in (
        as_inspector.json()["detail"]
    )
    panel = world["client"].get(
        f"/api/design-workshop-inspections/{workshop_id}/inspectors",
        headers=_headers(world, "master"),
    ).json()
    assert [row["userId"] for row in panel["inspectors"]] == [_id(world, "admin")]


@needs_db
def test_an_author_never_inspects_or_supervises_the_workshop(world):
    workshop_id = _open(world, "Authored")
    assert _designers(world, workshop_id, ["ministry"]).status_code == 200
    for response in (
        _inspectors(world, workshop_id, ["ministry"]),
        _officers(world, workshop_id, ad="ministry"),
    ):
        assert response.status_code == 409, response.text
        assert "holds designer access to this workshop" in response.json()["detail"]

    assert _write(world, workshop_id, "master2").status_code == 200
    stage_writer = _inspectors(world, workshop_id, ["master2"])
    assert stage_writer.status_code == 409, stage_writer.text
    assert "has written this workshop's stages" in stage_writer.json()["detail"]


@needs_db
def test_opening_a_workshop_is_not_authoring_it(world):
    inspected = _open(world, "Opened, then inspected")
    appointed = _inspectors(world, inspected, ["opener"])
    assert appointed.status_code == 200, appointed.text

    supervised = _open(world, "Opened, then supervised")
    assert _officers(world, supervised, ad="opener").status_code == 200

    refused = _write(world, inspected, "opener")
    assert refused.status_code == 403, refused.text
    assert "You are this workshop's inspector" in refused.json()["detail"]


@needs_db
def test_a_post_holder_is_refused_designer_access_to_the_workshop(world):
    workshop_id = _open(world, "Post holder as designer")
    assert _inspectors(world, workshop_id, ["admin"]).status_code == 200
    assert _officers(world, workshop_id, ad="ministry").status_code == 200

    for response in (
        _viewers(world, workshop_id, ["admin"]),
        _designers(world, workshop_id, ["admin"]),
        _viewers(world, workshop_id, ["ministry"]),
        _designers(world, workshop_id, ["ministry"]),
    ):
        assert response.status_code == 409, response.text
        assert "cannot also be given designer access" in response.json()["detail"]


@needs_db
def test_a_post_holder_cannot_write_the_workshop_even_through_the_admin_route(world):
    workshop_id = _open(world, "Read only while serving")
    assert _inspectors(world, workshop_id, ["admin"]).status_code == 200
    assert _officers(world, workshop_id, ad="master2").status_code == 200

    stage = _write(world, workshop_id, "admin")
    assert stage.status_code == 403, stage.text
    assert "You are this workshop's inspector" in stage.json()["detail"]
    header = world["client"].patch(
        f"/api/design-workshops/{workshop_id}",
        json={"title": "Renamed by its inspector"},
        headers=_headers(world, "admin"),
    )
    assert header.status_code == 403, header.text
    delete = world["client"].delete(
        f"/api/design-workshops/{workshop_id}", headers=_headers(world, "admin")
    )
    assert delete.status_code == 403, delete.text

    supervisor = _write(world, workshop_id, "master2")
    assert supervisor.status_code == 403, supervisor.text
    assert "You are this workshop's Assistant Director" in supervisor.json()["detail"]

    read = world["client"].get(
        f"/api/design-workshops/{workshop_id}", headers=_headers(world, "admin")
    )
    assert read.status_code == 200, read.text

    assert _inspectors(world, workshop_id, []).status_code == 200
    assert _officers(world, workshop_id, ad=None).status_code == 200
    assert _write(world, workshop_id, "admin").status_code == 200
    assert _write(world, workshop_id, "master2").status_code == 200


@needs_db
def test_a_content_admin_holding_a_post_is_still_refused_the_workshop(world):
    workshop_id = _open(world, "Content admin serving")
    assert _officers(world, workshop_id, ad="ministry").status_code == 200

    stage = _write(world, workshop_id, "ministry")
    assert stage.status_code == 403, stage.text
    assert "You are this workshop's Assistant Director" in stage.json()["detail"]
    delete = world["client"].delete(
        f"/api/design-workshops/{workshop_id}", headers=_headers(world, "ministry")
    )
    assert delete.status_code == 403, delete.text

    assert _officers(world, workshop_id, ad=None).status_code == 200
    assert _write(world, workshop_id, "ministry").status_code == 200


@needs_db
def test_a_post_holder_cannot_import_an_artisan_list_into_the_workshop(world):
    workshop_id = _open(world, "Artisans while serving")
    assert _officers(world, workshop_id, rd="ministry").status_code == 200

    def upload(slug: str):
        return world["client"].post(
            f"/api/design-workshop-oversight/{workshop_id}/artisans/upload",
            files={"file": ("artisans.xlsx", b"never read", "application/octet-stream")},
            headers=_headers(world, slug),
        )

    refused = upload("ministry")
    assert refused.status_code == 403, refused.text
    assert "You are this workshop's Regional Director" in refused.json()["detail"]

    passed_the_gate = upload("master")
    assert passed_the_gate.status_code == 422, passed_the_gate.text
    assert "no venue location" in passed_the_gate.json()["detail"]


@needs_db
def test_the_three_tiers_serve_exactly_as_they_did(world):
    workshop_id = _open(world, "The tiers as before")
    assert _officers(world, workshop_id, ad="ad", rd="rd").status_code == 200
    assert _inspectors(world, workshop_id, ["inspector"]).status_code == 200
    assert _designers(world, workshop_id, ["designer"]).status_code == 200

    for officer in ("ad", "rd"):
        assert workshop_id in _listed(world, "/api/design-workshop-oversight/assigned", officer)
    assert workshop_id in _listed(world, "/api/design-workshop-inspections", "inspector")
    assert _write(world, workshop_id, "designer").status_code == 200

    assert _write(world, workshop_id, "inspector").status_code == 403
    assert _write(world, workshop_id, "ad").status_code == 404
    other = _open(world, "The tiers in the wrong post")
    wrong_slot = _officers(world, other, ad="rd")
    assert wrong_slot.status_code == 422, wrong_slot.text
    wrong_panel = _inspectors(world, other, ["ad"])
    assert wrong_panel.status_code == 422, wrong_panel.text


@needs_db
def test_an_administrator_holding_no_post_sees_two_empty_lists_and_a_designer_is_refused(world):
    assert _listed(world, "/api/design-workshop-inspections", "master") == []
    assert _listed(world, "/api/design-workshop-oversight/assigned", "master") == []
    for path in ("/api/design-workshop-inspections", "/api/design-workshop-oversight/assigned"):
        refused = world["client"].get(path, headers=_headers(world, "designer"))
        assert refused.status_code == 403, refused.text


@needs_db
def test_the_pickers_offer_the_administrator_tiers_and_never_the_person_appointing(world):
    tag = world["tag"]

    def offered(path: str) -> dict[str, dict[str, Any]]:
        response = world["client"].get(
            path, params={"search": tag}, headers=_headers(world, "master")
        )
        assert response.status_code == 200, response.text
        return {row["id"]: row for row in response.json()["users"]}

    officers = offered("/api/design-workshop-oversight/officers")
    for slug in ("ministry", "admin", "master2", "opener", "ministryOpener", "ad", "rd"):
        assert _id(world, slug) in officers, slug
    for slug in ("master", "inspector", "designer"):
        assert _id(world, slug) not in officers, slug
    both = ["ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR"]
    assert officers[_id(world, "ministry")]["capacities"] == both
    assert officers[_id(world, "admin")]["capacities"] == both
    assert officers[_id(world, "ad")]["capacities"] == ["ASSISTANT_DIRECTOR"]
    assert officers[_id(world, "rd")]["capacities"] == ["REGIONAL_DIRECTOR"]

    inspectors = offered("/api/design-workshop-inspections/eligible-inspectors")
    for slug in ("inspector", "ministry", "admin", "master2", "opener"):
        assert _id(world, slug) in inspectors, slug
    for slug in ("master", "ad", "rd", "designer"):
        assert _id(world, slug) not in inspectors, slug

    for path in ("/api/design-workshops/eligible-viewers", "/api/design-workshop-oversight/designers"):
        designers = offered(path)
        for slug in ("ministry", "ad", "rd", "admin", "master2", "designer"):
            assert _id(world, slug) in designers, (path, slug)
        assert _id(world, "inspector") not in designers, path


@needs_db
def test_the_ministry_dashboard_counts_a_creator_only_where_they_wrote_the_workshop(world):
    opened = world["client"].post(
        "/api/design-workshop-oversight/workshops",
        json={"title": f"Opened by the ministry {world['tag']}"},
        headers=_headers(world, "ministryOpener"),
    )
    assert opened.status_code == 201, opened.text
    workshop_id = opened.json()["id"]

    def register_row() -> dict[str, Any] | None:
        wanted = _id(world, "ministryOpener")
        page, pages = 1, 1
        while page <= pages:
            response = world["client"].get(
                "/api/ministry-dashboard/designers",
                params={"page": page, "pageSize": 100},
                headers=_headers(world, "master"),
            )
            assert response.status_code == 200, response.text
            payload = response.json()
            for row in payload["items"]:
                if row["id"] == wanted:
                    return row
            pages = int(payload["pages"])
            page += 1
        return None

    assert register_row() is None, "opening a workshop counted its opener as its designer"

    assert _write(world, workshop_id, "ministryOpener").status_code == 200
    row = register_row()
    assert row is not None, "a creator who wrote their workshop is missing from the register"
    assert row["workshopsCreated"] == 1



@needs_db
def test_a_post_holder_cannot_change_the_designer_team_or_unfile_an_artisan(world):
    workshop_id = _open(world, "Team while serving")
    client = world["client"]
    assert _officers(world, workshop_id, rd="ministry").status_code == 200
    assert _inspectors(world, workshop_id, ["admin"]).status_code == 200

    def name_the_designer(slug: str):
        return client.put(
            f"/api/design-workshop-oversight/{workshop_id}/designer",
            json={"designerId": _id(world, "designer")},
            headers=_headers(world, slug),
        )

    def unfile(slug: str):
        return client.delete(
            f"/api/design-workshop-oversight/{workshop_id}/artisans/no-such-artisan",
            headers=_headers(world, slug),
        )

    as_director = posts.write_refusal({"REGIONAL_DIRECTOR"})
    as_inspector = posts.write_refusal({"INSPECTOR"})
    for response, sentence in (
        (_designers(world, workshop_id, ["designer"], as_slug="ministry"), as_director),
        (name_the_designer("ministry"), as_director),
        (unfile("ministry"), as_director),
        (_viewers(world, workshop_id, ["designer"], as_slug="admin"), as_inspector),
    ):
        assert response.status_code == 403, response.text
        assert response.json()["detail"] == sentence

    staffing = client.get(
        f"/api/design-workshop-oversight/{workshop_id}", headers=_headers(world, "master")
    ).json()
    assert staffing["designers"] == [], "a refused team write landed anyway"

    assert _designers(world, workshop_id, ["designer"]).status_code == 200
    renamed = name_the_designer("master")
    assert renamed.status_code == 200, renamed.text
    unfiled = unfile("master")
    assert unfiled.status_code == 200, unfiled.text
    assert unfiled.json() == {"unlinked": False}
    assert _viewers(world, workshop_id, ["designer"]).status_code == 200


@needs_db
def test_a_post_holder_decides_no_access_request_and_prints_no_card_but_lists_them(world):
    workshop_id = world["askedWorkshop"]
    client = world["client"]
    assert _inspectors(world, workshop_id, ["admin"]).status_code == 200

    def decide(slug: str, decision: str):
        return client.post(
            f"/api/design-workshop-access/requests/{world['accessRequest']}/decide",
            json={"status": decision},
            headers=_headers(world, slug),
        )

    def print_a_card(slug: str):
        return client.post(
            "/api/design-workshop-access/grants",
            json={"recordId": workshop_id, "maxUses": 3},
            headers=_headers(world, slug),
        )

    for response in (decide("admin", "GRANTED"), decide("admin", "DENIED"), print_a_card("admin")):
        assert response.status_code == 403, response.text
        assert response.json()["detail"] == posts.write_refusal({"INSPECTOR"})

    cards = client.get(
        f"/api/design-workshop-access/grants/{workshop_id}", headers=_headers(world, "admin")
    )
    assert cards.status_code == 200, cards.text

    granted = decide("master", "GRANTED")
    assert granted.status_code == 200, granted.text
    assert granted.json()["status"] == "GRANTED"
    assert print_a_card("master").status_code == 201


@needs_db
def test_a_post_holder_keeps_reading_appointing_others_restoring_and_reporting(world):
    workshop_id = _open(world, "What a holder keeps")
    client = world["client"]
    assert _officers(world, workshop_id, rd="ministry").status_code == 200
    assert _inspectors(world, workshop_id, ["admin"]).status_code == 200

    assert _officers(world, workshop_id, as_slug="ministry", ad="ad").status_code == 200
    panel = _inspectors(world, workshop_id, ["admin", "inspector"], as_slug="ministry")
    assert panel.status_code == 200, panel.text
    self_named = _officers(world, workshop_id, as_slug="ministry", ad="ministry")
    assert self_named.status_code == 409, "the separation rules stopped applying to a holder"

    for slug, path in (
        ("ministry", f"/api/design-workshop-oversight/{workshop_id}/artisans"),
        ("ministry", f"/api/design-workshop-oversight/assigned/{workshop_id}"),
        ("admin", f"/api/design-workshops/{workshop_id}"),
        ("admin", f"/api/design-workshops/{workshop_id}/viewers"),
        ("admin", f"/api/design-workshop-inspections/{workshop_id}"),
    ):
        read = client.get(path, headers=_headers(world, slug))
        assert read.status_code == 200, (path, read.text)

    report = client.post(
        f"/api/design-workshops/{workshop_id}/report",
        json={"formats": ["DOCX"], "record": True},
        headers=_headers(world, "admin"),
    )
    assert report.status_code == 200, report.text
    recorded = client.post(
        f"/api/design-workshops/{workshop_id}/exports",
        json={
            "format": "DOCX",
            "templateId": "default",
            "fileName": "made-by-its-inspector.docx",
            "generatedAt": "2026-10-09T00:00:00Z",
        },
        headers=_headers(world, "admin"),
    )
    assert recorded.status_code == 201, recorded.text
    ledger = client.get(
        f"/api/design-workshops/{workshop_id}/exports", headers=_headers(world, "admin")
    )
    assert ledger.status_code == 200, ledger.text
    assert len(ledger.json()) == 2, "one of the two ledger rows was not written"

    deleted = client.delete(
        f"/api/design-workshops/{workshop_id}", headers=_headers(world, "master")
    )
    assert deleted.status_code == 204, deleted.text
    restored = client.post(
        f"/api/design-workshops/{workshop_id}/restore", headers=_headers(world, "admin")
    )
    assert restored.status_code == 200, restored.text


@needs_db
def test_the_designer_pickers_leave_the_reader_out_only_for_a_workshop_that_exists(world):
    workshop_id = _open(world, "Picked for an existing workshop")
    tag = world["tag"]
    me = _id(world, "master")

    def offered(path: str, **params: str) -> set[str]:
        response = world["client"].get(
            path, params={"search": tag, **params}, headers=_headers(world, "master")
        )
        assert response.status_code == 200, response.text
        return {row["id"] for row in response.json()["users"]}

    for path in ("/api/design-workshops/eligible-viewers", "/api/design-workshop-oversight/designers"):
        assert me in offered(path), path
        on_the_workshop = offered(path, workshopId=workshop_id)
        assert me not in on_the_workshop, path
        assert _id(world, "designer") in on_the_workshop, path

    created = world["client"].post(
        "/api/design-workshops",
        json={"title": f"Named myself {tag}", "designerUserIds": [me, _id(world, "designer")]},
        headers=_headers(world, "master"),
    )
    assert created.status_code == 201, created.text
    staffing = world["client"].get(
        f"/api/design-workshop-oversight/{created.json()['id']}", headers=_headers(world, "master")
    ).json()
    assert [row["userId"] for row in staffing["designers"]] == [_id(world, "designer")], (
        "the creator was given a viewer row, or the designer they named beside themselves was not"
    )


LOCATION = {"latitude": 21.19, "longitude": 83.58, "state": "Odisha", "district": "Bargarh"}


def _fresh_aadhaar() -> str:
    import random

    from app.services.artisan_identity import verhoeff_ok

    stem = str(random.randint(2, 9)) + "".join(random.choices("0123456789", k=10))
    return next(stem + last for last in "0123456789" if verhoeff_ok(stem + last))


def _new_record_body(world: dict[str, Any], kind: str, workshop_id: str) -> dict[str, Any]:
    named = {"place": "Barpali", "artisanName": "Filed by the panel", "craftName": "Sambalpuri Ikat"}
    body = {
        "artisan": {
            "name": "Filed by the panel",
            "place": "Barpali",
            "aadhaarNumber": _fresh_aadhaar(),
            "dos": "Greet the master weaver first.",
            "donts": "Do not photograph the loom without asking.",
            "craftId": world["craft"],
            "location": LOCATION,
        },
        "product": {**named, "productName": "Ikat stole", "location": LOCATION},
        "process": {"name": "Warp tying", "productId": world["parentProduct"]},
        "tool": {**named, "toolkitName": "Pit loom", "location": LOCATION},
        "interview": {"title": "Sitting filed by the panel", "location": LOCATION},
        "questionnaire": {"title": "Form filed by the panel"},
    }[kind]
    return {**body, "designWorkshopId": workshop_id}


def _refile(world: dict[str, Any], kind: str, record_id: str, workshop_id: str | None, slug: str):
    return world["client"].patch(
        f"{RECORD_PATHS[kind]}/{record_id}",
        json={"designWorkshopId": workshop_id},
        headers=_headers(world, slug),
    )


def _filed_under(world: dict[str, Any], kind: str, workshop_id: str) -> set[str]:
    response = world["client"].get(
        RECORD_PATHS[kind],
        params={"designWorkshopId": workshop_id},
        headers=_headers(world, "master"),
    )
    assert response.status_code == 200, response.text
    return {item["id"] for item in response.json()["items"]}


@needs_db
@pytest.mark.parametrize("kind", list(RECORD_PATHS))
def test_a_post_holder_files_no_record_into_the_workshop_and_takes_none_out(world, kind):
    filing = world["filing"][kind]["refused"]
    held, free = filing["held"], filing["free"]
    assert _inspectors(world, held, ["admin"]).status_code == 200
    assert _officers(world, held, rd="ministry").status_code == 200
    as_inspector = posts.write_refusal({"INSPECTOR"})

    for record_id, workshop_id, what in (
        (filing["atHeld"], None, "unfile"),
        (filing["atHeld"], free, "move out"),
        (filing["atFree"], held, "move in"),
        (filing["loose"], held, "file a loose record in"),
    ):
        refused = _refile(world, kind, record_id, workshop_id, "admin")
        assert refused.status_code == 403, (what, refused.text)
        assert refused.json()["detail"] == as_inspector, what
    created = world["client"].post(
        RECORD_PATHS[kind],
        json=_new_record_body(world, kind, held),
        headers=_headers(world, "admin"),
    )
    assert created.status_code == 403, created.text
    assert created.json()["detail"] == as_inspector

    if kind == "questionnaire":
        copied = world["client"].post(
            f"{RECORD_PATHS[kind]}/{filing['atFree']}/reuse",
            json={"designWorkshopId": held},
            headers=_headers(world, "admin"),
        )
        assert copied.status_code == 403, copied.text
        assert copied.json()["detail"] == as_inspector
    else:
        director = _refile(world, kind, filing["atHeld"], None, "ministry")
        assert director.status_code == 403, director.text
        assert director.json()["detail"] == posts.write_refusal({"REGIONAL_DIRECTOR"})

    assert _filed_under(world, kind, held) == {filing["atHeld"]}, "a refused write landed"
    assert _filed_under(world, kind, free) == {filing["atFree"]}, "a refused write landed"


@needs_db
@pytest.mark.parametrize("kind", list(RECORD_PATHS))
def test_the_refusal_is_the_post_so_everybody_else_and_the_holder_off_it_still_refile(world, kind):
    filing = world["filing"][kind]["kept"]
    held, free = filing["held"], filing["free"]
    assert _inspectors(world, held, ["admin"]).status_code == 200
    assert _officers(world, held, rd="ministry").status_code == 200

    for workshop_id in (free, None, held):
        moved = _refile(world, kind, filing["atHeld"], workshop_id, "master")
        assert moved.status_code == 200, (workshop_id, moved.text)
    assert _filed_under(world, kind, held) == {filing["atHeld"]}

    for workshop_id in (free, None):
        loose = _refile(world, kind, filing["loose"], workshop_id, "admin")
        assert loose.status_code == 200, (workshop_id, loose.text)
    created = world["client"].post(
        RECORD_PATHS[kind],
        json=_new_record_body(world, kind, free),
        headers=_headers(world, "admin"),
    )
    assert created.status_code == 201, created.text
    assert _filed_under(world, kind, free) == {filing["atFree"], created.json()["id"]}

    assert _inspectors(world, held, []).status_code == 200
    moved_in = _refile(world, kind, filing["atFree"], held, "admin")
    assert moved_in.status_code == 200, moved_in.text
    assert _filed_under(world, kind, held) == {filing["atHeld"], filing["atFree"]}
    unfiled = _refile(world, kind, filing["atFree"], None, "admin")
    assert unfiled.status_code == 200, unfiled.text
    assert _filed_under(world, kind, held) == {filing["atHeld"]}


FIELD_EDITS: dict[str, dict[str, Any]] = {
    "artisan": {"place": "Sambalpur"},
    "product": {"place": "Sambalpur"},
    "process": {"notes": "Re-measured on the second visit."},
    "tool": {"place": "Sambalpur"},
    "interview": {"notes": "Re-measured on the second visit."},
    "questionnaire": {"title": "Renamed on the second visit"},
}


@needs_db
@pytest.mark.parametrize("kind", list(RECORD_PATHS))
def test_a_post_holder_edits_and_deletes_no_record_filed_under_their_workshop(world, kind):
    filing = world["filing"][kind]["edited"]
    held = filing["held"]
    client = world["client"]
    path = f"{RECORD_PATHS[kind]}/{filing['atHeld']}"
    assert _inspectors(world, held, ["admin"]).status_code == 200
    as_inspector = posts.write_refusal({"INSPECTOR"})
    admin = _headers(world, "admin")

    refused = [client.patch(path, json=FIELD_EDITS[kind], headers=admin)]
    if kind == "questionnaire":
        refused += [
            client.patch(path, json={"isActive": False}, headers=admin),
            client.post(f"{path}/sections", json={"title": "Added by its inspector"}, headers=admin),
            client.post(
                f"{path}/upload",
                files={"file": ("form.xlsx", b"never read", "application/octet-stream")},
                headers=admin,
            ),
        ]
    else:
        refused.append(client.delete(path, headers=admin))
    for response in refused:
        assert response.status_code == 403, response.text
        assert response.json()["detail"] == as_inspector
    seeded = {filing[name] for name in ("atHeld", "mergeHeld") if name in filing}
    assert _filed_under(world, kind, held) == seeded, "a refused write landed"

    next_door = client.patch(
        f"{RECORD_PATHS[kind]}/{filing['atFree']}", json=FIELD_EDITS[kind], headers=admin
    )
    assert next_door.status_code == 200, next_door.text
    by_master = client.patch(path, json=FIELD_EDITS[kind], headers=_headers(world, "master"))
    assert by_master.status_code == 200, by_master.text
    if kind != "questionnaire":
        loose = client.delete(f"{RECORD_PATHS[kind]}/{filing['loose']}", headers=admin)
        assert loose.status_code == 204, loose.text


@needs_db
def test_a_post_holder_merges_no_sitting_on_either_side_of_their_workshop(world):
    filing = world["filing"]["interview"]["edited"]
    assert _inspectors(world, filing["held"], ["admin"]).status_code == 200
    client = world["client"]

    def merge(source: str, target: str):
        return client.post(
            f"/api/questionnaire/interviews/{filing[source]}/merge-into/{filing[target]}",
            headers=_headers(world, "admin"),
        )

    for source, target in (
        ("mergeHeld", "atHeld"),
        ("mergeHeld", "mergeFree"),
        ("mergeFree", "mergeHeld"),
    ):
        refused = merge(source, target)
        assert refused.status_code == 403, (source, target, refused.text)
        assert refused.json()["detail"] == posts.write_refusal({"INSPECTOR"})
    assert _filed_under(world, "interview", filing["held"]) == {
        filing["atHeld"],
        filing["mergeHeld"],
    }, "a refused merge deleted a sitting"

    merged = merge("mergeFreeToo", "mergeFree")
    assert merged.status_code == 200, merged.text
    assert merged.json()["id"] == filing["mergeFree"]


@needs_db
def test_a_post_holder_changes_no_artisan_link_of_a_tool_filed_under_their_workshop(world):
    tools = world["filing"]["tool"]["edited"]
    artisan = world["filing"]["artisan"]["edited"]["atFree"]
    assert _inspectors(world, tools["held"], ["admin"]).status_code == 200
    client, admin = world["client"], _headers(world, "admin")

    for response in (
        client.post(
            f"/api/tools/{tools['atHeld']}/artisans", json={"artisanIds": [artisan]}, headers=admin
        ),
        client.delete(f"/api/tools/{tools['atHeld']}/artisans/{artisan}", headers=admin),
    ):
        assert response.status_code == 403, response.text
        assert response.json()["detail"] == posts.write_refusal({"INSPECTOR"})

    linked = client.post(
        f"/api/tools/{tools['atFree']}/artisans", json={"artisanIds": [artisan]}, headers=admin
    )
    assert linked.status_code == 200, linked.text
    assert [row["id"] for row in linked.json()] == [artisan]


@needs_db
def test_a_post_holder_changes_no_file_their_workshop_holds_by_any_door(world, monkeypatch):
    from app.api.routes import media as media_routes

    files = world["media"]
    held = files["held"]
    assert _inspectors(world, held, ["admin"]).status_code == 200
    assert _officers(world, held, rd="ministry").status_code == 200
    client, admin = world["client"], _headers(world, "admin")
    removed: list[str] = []
    monkeypatch.setattr(media_routes, "delete_object", removed.append)

    def relink(media: str, record: str, headers: dict[str, str]):
        return client.post(
            f"/api/media/{files[media]}/relink",
            json={"linkedRecordType": "artisan", "linkedRecordId": files[record]},
            headers=headers,
        )

    def decide(media: str, decision: str):
        return client.post(
            "/api/design-workshops/ocr/identity/retention",
            json={"mediaId": files[media], "decision": decision},
            headers=admin,
        )

    as_inspector = posts.write_refusal({"INSPECTOR"})
    for what, response in (
        *(
            (f"delete {name}", client.delete(f"/api/media/{files[name]}", headers=admin))
            for name in ("tagged", "staged", "rich", "filed", "onRecord")
        ),
        (
            "transcript",
            client.post(
                f"/api/media/{files['filed']}/transcript",
                json={"text": "Rewritten by its inspector."},
                headers=admin,
            ),
        ),
        (
            "refine",
            client.post(
                f"/api/media/{files['filed']}/refine-transcript",
                json={"translate": False},
                headers=admin,
            ),
        ),
        ("transcribe now", client.post(f"/api/media/{files['filed']}/transcribe-now", headers=admin)),
        ("keep the identity photograph", decide("tagged", "STORE")),
        ("discard the identity photograph", decide("staged", "DISCARD")),
        ("relink out of the workshop", relink("tagged", "unfiled", admin)),
        ("relink into the workshop", relink("freeTagged", "pictured", admin)),
    ):
        assert response.status_code == 403, (what, response.text)
        assert response.json()["detail"] == as_inspector, what

    own = client.delete(
        f"/api/media/{files['ministryOwn']}", headers=_headers(world, "ministry")
    )
    assert own.status_code == 403, own.text
    assert own.json()["detail"] == posts.write_refusal({"REGIONAL_DIRECTOR"})

    master = _headers(world, "master")
    for name in ("tagged", "staged", "rich", "filed", "onRecord", "ministryOwn", "freeTagged"):
        still = client.get(f"/api/media/{files[name]}", headers=master)
        assert still.status_code == 200, (name, still.text)
    assert client.get(f"/api/media/{files['tagged']}", headers=master).json()[
        "linkedRecordType"
    ] == "designWorkshop", "a refused relink moved the file"
    recording = client.get(f"/api/media/{files['filed']}", headers=master).json()
    assert recording["transcriptText"] == "As the artisan said it.", "a refused rewrite landed"
    assert recording["transcriptEditedById"] is None
    assert removed == [], "a refused delete reached object storage"

    rewritten = client.post(
        f"/api/media/{files['freeAudio']}/transcript",
        json={"text": "Corrected on the second visit."},
        headers=admin,
    )
    assert rewritten.status_code == 200, rewritten.text
    assert rewritten.json()["transcriptText"] == "Corrected on the second visit."
    assert relink("freeMoved", "unfiled", admin).status_code == 200
    kept = decide("freeKept", "STORE")
    assert kept.status_code == 200, kept.text
    assert kept.json()["retention"]["decision"] == "STORE"
    deleted = client.delete(f"/api/media/{files['freeDeleted']}", headers=admin)
    assert deleted.status_code == 204, deleted.text
    assert removed == [f"media/{_id(world, 'master')}/serve-as-{world['tag']}-free-deleted"]
    by_master = client.post(
        f"/api/media/{files['filed']}/transcript",
        json={"text": "Corrected by the master admin."},
        headers=master,
    )
    assert by_master.status_code == 200, by_master.text


@needs_db
def test_a_post_holder_who_scans_their_workshops_join_card_lands_provisional_and_spends_nothing(
    world,
):
    workshop_id = _open(world, "Join card while serving")
    client = world["client"]
    assert _inspectors(world, workshop_id, ["ministry"]).status_code == 200
    minted = client.post(
        "/api/design-workshop-access/grants",
        json={"recordId": workshop_id, "maxUses": 1},
        headers=_headers(world, "master"),
    )
    assert minted.status_code == 201, minted.text

    scanned = client.post(
        "/api/design-workshop-access/redemptions",
        json={"code": minted.json()["code"]},
        headers=_headers(world, "ministry"),
    )
    assert scanned.status_code == 200, scanned.text
    assert (scanned.json()["outcome"], scanned.json()["reason"]) == ("PROVISIONAL", "INELIGIBLE")
    assert "already been used" not in scanned.json()["detail"], scanned.json()["detail"]
    assert "the card was not used up" in scanned.json()["detail"]
    assert "inspector" not in scanned.json()["detail"]

    cards = client.get(
        f"/api/design-workshop-access/grants/{workshop_id}", headers=_headers(world, "master")
    )
    assert cards.status_code == 200, cards.text
    assert [card["usesConsumed"] for card in cards.json()["grants"]] == [0]
    viewers = client.get(
        f"/api/design-workshops/{workshop_id}/viewers", headers=_headers(world, "master")
    )
    assert viewers.status_code == 200, viewers.text
    assert _id(world, "ministry") not in {row["userId"] for row in viewers.json()["viewers"]}



def _read_back(world: dict[str, Any], review_type: str, record_id: str) -> dict[str, Any]:
    path = "/api/media" if review_type == "media" else RECORD_PATHS[REVIEWED[review_type][0]]
    response = world["client"].get(f"{path}/{record_id}", headers=_headers(world, "master"))
    assert response.status_code == 200, response.text
    return response.json()


@needs_db
@pytest.mark.parametrize("review_type", list(REVIEWED))
def test_a_post_holder_rewrites_nothing_of_their_workshop_from_the_review_queue(world, review_type):
    doors = world["doors"]
    held, free = doors["held"], doors["free"]
    assert _inspectors(world, held, ["admin"]).status_code == 200
    client = world["client"]
    (field, value), = REVIEWED[review_type][1].items()
    at = doors[review_type]

    def edit(record_id: str, slug: str, fields: dict[str, Any]) -> Any:
        return client.post(
            f"/api/review/{review_type}/{record_id}/edit",
            json={"fields": fields, "note": "From the review queue."},
            headers=_headers(world, slug),
        )

    refused = edit(at["held"], "admin", {field: value})
    assert refused.status_code == 403, refused.text
    assert refused.json()["detail"] == posts.write_refusal({"INSPECTOR"})
    assert _read_back(world, review_type, at["held"])[field] != value, "a refused edit landed"

    next_door = edit(at["free"], "admin", {field: value})
    assert next_door.status_code == 200, next_door.text
    assert _read_back(world, review_type, at["free"])[field] == value
    by_master = edit(at["held"], "master", {field: value})
    assert by_master.status_code == 200, by_master.text
    assert _read_back(world, review_type, at["held"])[field] == value

    if review_type != "media":
        refiled = edit(at["free"], "master", {"designWorkshopId": held})
        assert refiled.status_code == 422, refiled.text
        assert "designWorkshopId" in refiled.json()["detail"]
        assert _read_back(world, review_type, at["free"])["designWorkshopId"] == free


@needs_db
def test_a_post_holder_uploads_no_new_file_into_their_workshop(world, monkeypatch):
    from app.api.routes import media as media_routes

    monkeypatch.setattr(media_routes, "head_object", lambda _key: None)
    doors = world["doors"]
    held, free = doors["held"], doors["free"]
    assert _inspectors(world, held, ["admin"]).status_code == 200
    as_inspector = posts.write_refusal({"INSPECTOR"})

    def complete(slug: str, label: str, **links: Any) -> Any:
        user_id = _id(world, slug)
        return world["client"].post(
            "/api/media/complete",
            json={
                "originalFilename": f"{label}.jpg",
                "mediaType": "IMAGE",
                "mimeType": "image/jpeg",
                "sizeBytes": 2048,
                "objectKey": f"media/{user_id}/serve-as-{world['tag']}-upload-{label}.jpg",
                **links,
            },
            headers=_headers(world, slug),
        )

    tagged = {"linkedRecordType": "designWorkshop", "linkedRecordId": held}
    on_record = {"linkedRecordType": "artisan", "linkedRecordId": doors["artisan"]["held"]}
    for label, links in (("tagged", tagged), ("on-record", on_record)):
        for attempt in (1, 2):
            refused = complete("admin", f"refused-{label}", **links)
            assert refused.status_code == 403, (label, attempt, refused.text)
            assert refused.json()["detail"] == as_inspector, (label, attempt)

    next_door = complete(
        "admin", "next-door", linkedRecordType="designWorkshop", linkedRecordId=free
    )
    assert next_door.status_code == 201, next_door.text
    by_master = complete("master", "by-master", **tagged)
    assert by_master.status_code == 201, by_master.text
    assert by_master.json()["linkedRecordId"] == held


@needs_db
def test_a_post_holder_requeues_no_transcription_of_their_workshops_recording(world):
    doors = world["doors"]
    assert _inspectors(world, doors["held"], ["admin"]).status_code == 200
    client = world["client"]

    def retry(place: str, slug: str) -> Any:
        return client.post(
            f"/api/media/jobs/{doors['jobs'][place]}/retry", headers=_headers(world, slug)
        )

    refused = retry("held", "admin")
    assert refused.status_code == 403, refused.text
    assert refused.json()["detail"] == posts.write_refusal({"INSPECTOR"})

    by_master = retry("held", "master")
    assert by_master.status_code == 200, by_master.text
    assert by_master.json()["status"] == "QUEUED", "the refused retry had already moved the job"
    next_door = retry("free", "admin")
    assert next_door.status_code == 200, next_door.text
    assert next_door.json()["status"] == "QUEUED"



def _unfiled(world: dict[str, Any], method: str, bucket: str, name: str, slug: str, **body: Any):
    path = f"/api/workshops/unmapped/{bucket}/{world['unfiled'][name]}"
    send = world["client"].delete if method == "DELETE" else world["client"].post
    kwargs: dict[str, Any] = {"headers": _headers(world, slug)}
    if body:
        kwargs["json"] = body
    return send(path, **kwargs)


def _crafts_workshop_of(world: dict[str, Any], bucket: str, name: str) -> str | None:
    path = {"media": "/api/media", "products": "/api/products", "artisans": "/api/artisans"}[bucket]
    response = world["client"].get(
        f"{path}/{world['unfiled'][name]}", headers=_headers(world, "master")
    )
    assert response.status_code == 200, (name, response.text)
    return response.json()["workshopId"]


@needs_db
def test_the_unfiled_report_lists_no_row_a_design_workshop_claims(world):
    listed = world["unfiled"]["listed"]
    for name in ("tagged", "taggedLower", "filed", "filedArtisan", "filedProduct"):
        assert listed[name] is False, f"{name} is claimed by a design workshop and was listed"
    for name in ("staged", "onRecord", "otherTag", "loose", "looseArtisan", "looseProduct"):
        assert listed[name] is True, f"{name} needs a person and was not listed"


@needs_db
def test_the_batched_holder_question_answers_exactly_what_the_file_by_file_one_does(world):
    parity = world["unfiled"]["parity"]
    assert parity["batched"] == parity["fileByFile"]
    media, unfiled = world["media"], world["unfiled"]
    belongs = {
        *(media[key] for key in ("tagged", "staged", "rich", "filed", "onRecord", "ministryOwn")),
        *(unfiled[key] for key in ("tagged", "taggedLower", "filed", "staged", "onRecord", "mapHeld")),
    }
    stranger = {
        *(media[key] for key in ("freeTagged", "freeAudio")),
        *(unfiled[key] for key in ("otherTag", "loose", "looseHolder", "mapFree")),
    }
    assert set(parity["batched"]) == belongs, "the fixture stopped exercising every way a file belongs"
    assert parity["asked"] >= len(belongs | stranger)


@needs_db
def test_nobody_discards_from_the_unfiled_report_a_row_a_design_workshop_claims(world, monkeypatch):
    from app.services import workshop_inference

    held = world["unfiled"]["held"]
    assert _inspectors(world, held, ["admin"]).status_code == 200
    removed: list[str] = []
    monkeypatch.setattr(workshop_inference, "delete_object", removed.append)
    as_inspector = posts.write_refusal({"INSPECTOR"})
    claimed = (
        ("media", "tagged"),
        ("media", "taggedLower"),
        ("media", "filed"),
        ("media", "staged"),
        ("media", "onRecord"),
        ("artisans", "filedArtisan"),
        ("products", "filedProduct"),
    )
    for bucket, name in claimed:
        refused = _unfiled(world, "DELETE", bucket, name, "admin")
        assert refused.status_code == 403, (name, refused.text)
        assert refused.json()["detail"] == as_inspector, name
        for slug in ("opener", "master"):
            claimed_answer = _unfiled(world, "DELETE", bucket, name, slug)
            assert claimed_answer.status_code == 409, (name, slug, claimed_answer.text)
            detail = claimed_answer.json()["detail"]
            assert f"“{world['unfiled']['heldTitle']}”" in detail, (name, detail)
            assert "is not deleted from here" in detail and "Open the" in detail, detail
        assert _crafts_workshop_of(world, bucket, name) is None, f"{name} was deleted or moved"
    assert removed == [], "a refused discard reached object storage"

    gone = _unfiled(world, "DELETE", "media", "loose", "opener")
    assert gone.status_code == 200, gone.text
    assert gone.json()["mediaKept"] == 0
    assert removed == [f"media/{_id(world, 'master')}/serve-as-{world['tag']}-unfiled-loose"]
    by_the_holder = _unfiled(world, "DELETE", "products", "looseDiscard", "admin")
    assert by_the_holder.status_code == 200, by_the_holder.text


@needs_db
def test_a_post_holder_files_no_row_of_their_workshop_from_the_unfiled_report(world):
    unfiled = world["unfiled"]
    assert _inspectors(world, unfiled["held"], ["admin"]).status_code == 200
    target = {"workshopId": unfiled["crafts"]}
    for bucket, name in (("products", "filedProduct"), ("media", "staged"), ("media", "tagged")):
        refused = _unfiled(world, "POST", bucket, name, "admin", **target)
        assert refused.status_code == 403, (name, refused.text)
        assert refused.json()["detail"] == posts.write_refusal({"INSPECTOR"}), name
        assert _crafts_workshop_of(world, bucket, name) is None, f"a refused file of {name} landed"

    for name, slug in (("looseProductHolder", "admin"), ("looseProduct", "opener")):
        filed = _unfiled(world, "POST", "products", name, slug, **target)
        assert filed.status_code == 200, (name, filed.text)
        assert filed.json()["workshopId"] == unfiled["crafts"]
        assert _crafts_workshop_of(world, "products", name) == unfiled["crafts"]


@needs_db
def test_a_holders_bulk_map_leaves_their_workshops_rows_alone_and_says_so(world, monkeypatch):
    from app.services import workshop_inference as inference

    unfiled = world["unfiled"]
    crafts = unfiled["crafts"]
    assert _inspectors(world, unfiled["held"], ["admin"]).status_code == 200
    resolved = {
        "media": ("mapHeld", "mapFree"),
        "products": ("mapRecordHeld", "mapProduct"),
    }

    async def _ladder() -> Any:
        plans = {bucket: inference.BucketPlan(bucket=bucket) for bucket in inference.BUCKET_KEYS}
        for bucket, names in resolved.items():
            plans[bucket].unassigned = len(names)
            plans[bucket].rows = [
                inference.RowPlan(
                    id=unfiled[name], title=name, workshopId=crafts, rung=inference.RUNG_PARENT
                )
                for name in names
            ]
        return inference.LadderRun(
            plans=plans, workshopTitles={crafts: "Crafts workshop"}, windows=[]
        )

    monkeypatch.setattr(inference, "run_ladder", _ladder)

    def run(slug: str) -> dict[str, Any]:
        response = world["client"].post(
            "/api/workshops/unmapped/map", headers=_headers(world, slug)
        )
        assert response.status_code == 200, response.text
        body = response.json()
        return {"body": body, **{b["bucket"]: b for b in body["buckets"]}}

    by_the_holder = run("admin")
    for bucket in resolved:
        assert (by_the_holder[bucket]["heldBack"], by_the_holder[bucket]["applied"]) == (1, 1), bucket
    assert by_the_holder["body"]["totals"]["heldBack"] == 2
    assert by_the_holder["body"]["heldBackDetail"] == inference.held_back_detail(2)
    assert "inspect or supervise" in by_the_holder["body"]["heldBackDetail"]
    assert _crafts_workshop_of(world, "media", "mapHeld") is None, "the holder's run stamped it"
    assert _crafts_workshop_of(world, "products", "mapRecordHeld") is None
    assert _crafts_workshop_of(world, "media", "mapFree") == crafts
    assert _crafts_workshop_of(world, "products", "mapProduct") == crafts

    by_master = run("master")
    for bucket in resolved:
        assert (by_master[bucket]["heldBack"], by_master[bucket]["applied"]) == (0, 1), bucket
    assert by_master["body"]["heldBackDetail"] is None
    assert _crafts_workshop_of(world, "media", "mapHeld") == crafts
    assert _crafts_workshop_of(world, "products", "mapRecordHeld") == crafts



@needs_db
def test_nobody_takes_themselves_off_an_inspection_or_oversight_post(world):
    workshop_id = _open(world, "Nobody takes themselves off")
    client, master = world["client"], _headers(world, "master")
    assert _inspectors(world, workshop_id, ["admin"]).status_code == 200
    assert _officers(world, workshop_id, ad="ministry", rd="master2").status_code == 200

    for response, post in (
        (_inspectors(world, workshop_id, [], as_slug="admin"), "INSPECTOR"),
        (_inspectors(world, workshop_id, ["inspector"], as_slug="admin"), "INSPECTOR"),
        (_officers(world, workshop_id, as_slug="ministry", ad=None), "ASSISTANT_DIRECTOR"),
        (_officers(world, workshop_id, as_slug="ministry", ad="ad"), "ASSISTANT_DIRECTOR"),
        (_officers(world, workshop_id, as_slug="master2", rd=None), "REGIONAL_DIRECTOR"),
        (_officers(world, workshop_id, as_slug="master2", rd="rd"), "REGIONAL_DIRECTOR"),
    ):
        assert response.status_code == 409, (post, response.text)
        assert response.json()["detail"] == posts.self_release_refusal({post}), post

    def panel() -> list[str]:
        response = client.get(
            f"/api/design-workshop-inspections/{workshop_id}/inspectors", headers=master
        )
        assert response.status_code == 200, response.text
        return [row["userId"] for row in response.json()["inspectors"]]

    def slots() -> dict[str, str]:
        response = client.get(f"/api/design-workshop-oversight/{workshop_id}", headers=master)
        assert response.status_code == 200, response.text
        return {row["capacity"]: row["userId"] for row in response.json()["oversight"]}

    assert panel() == [_id(world, "admin")], "a refused release took the inspector off"
    assert slots() == {
        "ASSISTANT_DIRECTOR": _id(world, "ministry"),
        "REGIONAL_DIRECTOR": _id(world, "master2"),
    }, "a refused release changed a slot"

    assert _inspectors(world, workshop_id, [], as_slug="ministry").status_code == 200
    assert panel() == []
    assert _officers(world, workshop_id, ad=None, rd=None).status_code == 200
    assert slots() == {}
