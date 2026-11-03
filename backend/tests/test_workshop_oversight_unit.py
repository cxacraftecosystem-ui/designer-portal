import ast
import asyncio
import inspect
import textwrap
from pathlib import Path
from types import SimpleNamespace

import pytest
from fastapi import HTTPException

from app.api.routes import design_workshop_oversight as routes
from app.core import deps
from app.schemas import design_workshop_review_loop as review_loop
from app.services import design_workshop_oversight as oversight

ALL_ROLES = tuple(deps.ROLE_RANK)

OFFICERS = {"ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR", "MINISTRY_ADMIN"}
ASSIGNERS = {"MINISTRY_ADMIN", "ADMIN", "MASTER_ADMIN"}

SERVING_ADMINS = {"MINISTRY_ADMIN", "ADMIN", "MASTER_ADMIN"}

HOLDERS = {"ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR"} | SERVING_ADMINS

CREATORS = {"ADMIN", "MASTER_ADMIN", "MINISTRY_ADMIN"}
INSPECTABLE = {"INSPECTOR"}


def user(role: str, **extra):
    return SimpleNamespace(
        id=f"u-{role}", name=role.title(), email=f"{role}@x.test", role=role, **extra
    )



def test_the_ladder_has_the_three_directorate_tiers_this_feature_is_built_on():
    assert set(ALL_ROLES) >= OFFICERS, sorted(OFFICERS - set(ALL_ROLES))
    assert deps.ROLE_RANK["ASSISTANT_DIRECTOR"] > deps.ROLE_RANK["PROFESSOR"]
    assert deps.ROLE_RANK["REGIONAL_DIRECTOR"] > deps.ROLE_RANK["ASSISTANT_DIRECTOR"]
    assert deps.ROLE_RANK["MINISTRY_ADMIN"] > deps.ROLE_RANK["REGIONAL_DIRECTOR"]


@pytest.mark.parametrize("role", ALL_ROLES)
def test_exactly_the_three_ministry_posts_are_officers(role):
    assert oversight.is_officer(user(role)) is (role in OFFICERS), role


@pytest.mark.parametrize("role", ALL_ROLES)
def test_exactly_the_roles_that_may_hold_a_slot_reach_the_officer_surface(role):
    assert oversight.may_hold_oversight(user(role)) is (role in HOLDERS), role
    assert frozenset(HOLDERS) == oversight.OVERSIGHT_HOLDER_ROLES


@pytest.mark.parametrize("role", ALL_ROLES)
def test_exactly_a_ministry_admin_and_the_two_platform_tiers_may_assign(role):
    assert oversight.can_assign_workshop_oversight(user(role)) is (role in ASSIGNERS), role


def test_a_regional_director_is_an_officer_and_is_not_an_assigner():
    rd = user("REGIONAL_DIRECTOR")
    assert oversight.is_officer(rd) is True
    assert oversight.can_assign_workshop_oversight(rd) is False


def test_neither_predicate_admits_a_missing_user():
    assert oversight.is_officer(None) is False
    assert oversight.can_assign_workshop_oversight(None) is False
    assert oversight.is_officer(SimpleNamespace(role=None)) is False


def test_a_designer_is_refused_the_officers_own_surface_and_an_admin_is_not():
    with pytest.raises(HTTPException) as exc:
        oversight.assert_oversight_surface(user("DESIGNER"))
    assert exc.value.status_code == 403
    assert "This page is for Assistant Directors and Regional Directors" in exc.value.detail
    assert "ask an administrator" in exc.value.detail
    for role in ("ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR", *sorted(SERVING_ADMINS)):
        oversight.assert_oversight_surface(user(role))


def test_the_assigner_refusal_names_the_screen_an_officer_should_use_instead():
    with pytest.raises(HTTPException) as exc:
        oversight.assert_may_assign_oversight(user("REGIONAL_DIRECTOR"))
    assert exc.value.status_code == 403
    assert "Workshops I monitor" in exc.value.detail
    oversight.assert_may_assign_oversight(user("MINISTRY_ADMIN"))



def test_each_directorate_post_fits_its_own_slot_and_an_administrator_fits_both():
    both = ["ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR"]
    assert tuple(both) == oversight.CAPACITIES
    assert oversight.capacities_for("ASSISTANT_DIRECTOR") == ["ASSISTANT_DIRECTOR"]
    assert oversight.capacities_for("REGIONAL_DIRECTOR") == ["REGIONAL_DIRECTOR"]
    for role in SERVING_ADMINS:
        assert oversight.capacities_for(role) == both, role
    for role in set(ALL_ROLES) - HOLDERS:
        assert oversight.capacities_for(role) == [], role
    assert frozenset(both) == oversight.DIRECTORATE_POST_ROLES


def test_every_capacity_the_enum_declares_has_a_role_that_may_hold_it():
    try:
        from prisma.enums import DwOversightCapacity
    except ImportError:  # pragma: no cover - a client older than the migration
        pytest.skip("the generated Prisma client predates DwOversightCapacity")
    assert {m.value for m in DwOversightCapacity} == set(oversight.OVERSIGHT_CAPACITY_ROLES)



def test_the_scope_clause_reads_its_own_relation_and_has_no_creator_arm():
    clause = oversight.oversight_by_clause("u1")
    assert clause == {"oversight": {"some": {"userId": "u1"}}}
    assert "viewers" not in repr(clause)
    assert "createdById" not in repr(clause)


def test_the_read_only_loader_has_no_for_edit_parameter():
    from app.services import design_workshops as workshop_service

    ours = inspect.signature(oversight.load_overseen_workshop_or_404).parameters
    assert "for_edit" not in ours
    theirs = inspect.signature(workshop_service.load_workshop_or_404).parameters
    assert "for_edit" in theirs, "the control: the sibling loader is still the one that edits"


class _Rows:
    def __init__(self, rows=None):
        self.rows = list(rows or [])

    @staticmethod
    def _matches(row, where):
        return all(getattr(row, key, None) == value for key, value in where.items())

    async def find_first(self, where, include=None):
        return next((r for r in self.rows if self._matches(r, where)), None)

    async def find_unique(self, where, include=None):
        return await self.find_first(where)


class _Client:
    def __init__(self, **tables):
        for name, table in tables.items():
            setattr(self, name, table)


WORKSHOP_ID = "cmoversightgate0000000w"
OFFICER_ID = "cmoversightgate0000000o"
STRANGER_ID = "cmoversightgate0000000s"


@pytest.fixture
def scoped(monkeypatch: pytest.MonkeyPatch):
    client = _Client(
        designworkshopoversight=_Rows(
            [SimpleNamespace(designWorkshopId=WORKSHOP_ID, userId=OFFICER_ID)]
        ),
        designworkshop=_Rows([SimpleNamespace(id=WORKSHOP_ID, deletedAt=None)]),
    )
    monkeypatch.setattr(oversight, "db", client)
    return client


def test_an_officer_with_no_oversight_row_sees_nothing_at_all(scoped):
    assert asyncio.run(oversight.has_oversight_scope(WORKSHOP_ID, OFFICER_ID)) is True
    assert asyncio.run(oversight.has_oversight_scope(WORKSHOP_ID, STRANGER_ID)) is False
    assert asyncio.run(oversight.has_oversight_scope("", OFFICER_ID)) is False
    assert asyncio.run(oversight.has_oversight_scope(WORKSHOP_ID, "")) is False


def test_the_loader_re_checks_the_role_even_behind_its_own_dependency(scoped):
    with pytest.raises(HTTPException) as exc:
        asyncio.run(
            oversight.load_overseen_workshop_or_404(
                WORKSHOP_ID, SimpleNamespace(id=OFFICER_ID, role="DESIGNER")
            )
        )
    assert exc.value.status_code == 404, "404 and not 403 — see the loader's docstring"
    assert exc.value.detail == "Record not found"


def test_an_administrator_holding_a_row_reads_that_workshop(scoped):
    record = asyncio.run(
        oversight.load_overseen_workshop_or_404(
            WORKSHOP_ID, SimpleNamespace(id=OFFICER_ID, role="ADMIN")
        )
    )
    assert record.id == WORKSHOP_ID
    with pytest.raises(HTTPException) as exc:
        asyncio.run(
            oversight.load_overseen_workshop_or_404(
                WORKSHOP_ID, SimpleNamespace(id=STRANGER_ID, role="MASTER_ADMIN")
            )
        )
    assert exc.value.status_code == 404, "an admin with no row was shown a workshop by rank"


def test_a_workshop_out_of_scope_is_a_404_that_reads_like_any_other(scoped):
    with pytest.raises(HTTPException) as exc:
        asyncio.run(
            oversight.load_overseen_workshop_or_404(
                WORKSHOP_ID, SimpleNamespace(id=STRANGER_ID, role="REGIONAL_DIRECTOR")
            )
        )
    assert (exc.value.status_code, exc.value.detail) == (404, "Record not found")


def test_a_soft_deleted_workshop_is_a_404_with_no_409_arm(scoped):
    scoped.designworkshop.rows = [SimpleNamespace(id=WORKSHOP_ID, deletedAt=object())]
    with pytest.raises(HTTPException) as exc:
        asyncio.run(
            oversight.load_overseen_workshop_or_404(
                WORKSHOP_ID, SimpleNamespace(id=OFFICER_ID, role="ASSISTANT_DIRECTOR")
            )
        )
    assert exc.value.status_code == 404



def _dependency_names(route) -> set[str]:
    seen: set[str] = set()
    stack = [route.dependant]
    while stack:
        node = stack.pop()
        if node.call is not None:
            seen.add(getattr(node.call, "__name__", ""))
        stack.extend(node.dependencies)
    return seen


def test_every_route_on_this_prefix_stands_behind_one_of_the_two_doors():
    for route in routes.router.routes:
        gates = _dependency_names(route)
        assert gates & {"require_officer", "require_workshop_assigner"}, (
            f"{route.path} is on the oversight router with neither door"
        )


OFFICER_NOTE_DOORS = {
    "/design-workshop-oversight/assigned/{workshop_id}/feedback",
    "/design-workshop-oversight/assigned/{workshop_id}/feedback/batch",
}


def test_the_officer_note_doors_exist_and_write_only_feedback():
    import inspect

    live = {
        route.path
        for route in routes.router.routes
        if "require_officer" in _dependency_names(route) and set(route.methods) != {"GET"}
    }
    assert live == OFFICER_NOTE_DOORS
    source = inspect.getsource(routes._file_officer_notes)
    assert "tx.dwinspectionfeedback.create" in source
    for table in ("dwstageentry", "designworkshop.update", "reviewlog"):
        assert table not in source, table


def test_every_route_an_officer_can_reach_is_a_get():
    for route in routes.router.routes:
        if "require_officer" in _dependency_names(route):
            if route.path in OFFICER_NOTE_DOORS:
                assert set(route.methods) == {"POST"}, route.path
                continue
            assert set(route.methods) == {"GET"}, (
                f"{route.methods} {route.path} is reachable by an officer and is not a GET. Read "
                f"the header of app/services/design_workshop_oversight.py before changing this "
                f"test: read-only here is structural, not a policy note."
            )


def test_every_literal_path_is_declared_before_the_workshop_id_route():
    paths = [route.path for route in routes.router.routes]
    first_parameterised = next(
        (i for i, path in enumerate(paths) if "{workshop_id}" in path), len(paths)
    )
    literals = [
        i
        for i, path in enumerate(paths)
        if "{" not in path.removeprefix("/design-workshop-oversight")
    ]
    assert literals, "the sweep must actually see the literal routes it is defending"
    assert max(literals) < first_parameterised, (
        f"a literal path is declared after {paths[first_parameterised]} and will be swallowed by "
        f"it: {[paths[i] for i in literals if i > first_parameterised]}"
    )


def test_the_prefix_is_not_the_one_the_shared_workshop_loader_guards():
    assert routes.router.prefix == "/design-workshop-oversight"
    for route in routes.router.routes:
        assert not route.path.startswith("/design-workshops/")



class _Upload:
    def __init__(self, filename: str | None, payload: bytes = b"") -> None:
        self.filename = filename
        self._payload = payload
        self._at = 0

    async def read(self, size: int = -1) -> bytes:
        if size < 0:
            chunk, self._at = self._payload[self._at :], len(self._payload)
            return chunk
        chunk = self._payload[self._at : self._at + size]
        self._at += size
        return chunk


def test_a_docx_is_refused_by_extension_before_the_body_is_read():
    upload = _Upload("roster.docx", b"PK\x03\x04" + b"x" * 100)
    with pytest.raises(HTTPException) as exc:
        asyncio.run(routes._read_artisan_upload(upload))
    assert exc.value.status_code == 415
    assert "artisan list pro-forma" in exc.value.detail
    assert "File > Save As" in exc.value.detail
    assert upload._at == 0, "the body must not have been read"


def test_a_part_with_no_filename_falls_through_to_the_magic_byte_check():
    content = asyncio.run(routes._read_artisan_upload(_Upload(None, b"%PDF-1.7\n")))
    assert content == b"%PDF-1.7\n", "no 415 — the extension gate did not fire"


def test_an_empty_upload_is_a_422_naming_the_pro_forma():
    with pytest.raises(HTTPException) as exc:
        asyncio.run(routes._read_artisan_upload(_Upload("roster.xlsx", b"")))
    assert exc.value.status_code == 422
    assert "artisan list pro-forma" in exc.value.detail


def test_an_oversized_artisan_upload_is_refused_at_four_megabytes_not_eight():
    from app.api.routes.questionnaire_forms import MAX_UPLOAD_BYTES

    assert routes.MAX_ARTISAN_UPLOAD_BYTES == 4 * 1024 * 1024
    assert MAX_UPLOAD_BYTES == 8 * 1024 * 1024
    assert routes.MAX_ARTISAN_UPLOAD_BYTES < MAX_UPLOAD_BYTES

    oversized = b"PK\x03\x04" + b"x" * routes.MAX_ARTISAN_UPLOAD_BYTES
    with pytest.raises(HTTPException) as exc:
        asyncio.run(routes._read_artisan_upload(_Upload("roster.xlsx", oversized)))
    assert exc.value.status_code == 413
    assert "4 MB" in exc.value.detail
    assert "artisan list" in exc.value.detail


def test_a_file_at_exactly_the_limit_is_accepted():
    at_limit = b"x" * routes.MAX_ARTISAN_UPLOAD_BYTES
    content = asyncio.run(routes._read_artisan_upload(_Upload("roster.xlsx", at_limit)))
    assert len(content) == routes.MAX_ARTISAN_UPLOAD_BYTES


def test_the_upload_door_calls_the_shared_bound_rather_than_counting_bytes_itself():
    source = inspect.getsource(routes._read_artisan_upload)
    assert "read_upload_bounded(" in source
    assert "while True" not in source, "a hand-rolled read loop is a second bound"



def test_the_oversight_designer_picker_carries_no_roster_columns():
    from app.services.designers import assignable_designers_payload, roster_directory_payload

    rows = [SimpleNamespace(id="d1", name="A Designer", email="d@x.test", role="DESIGNER")]
    [payload] = assignable_designers_payload(rows)
    assert set(payload) == {"id", "name", "email", "role"}

    assert roster_directory_payload is not assignable_designers_payload
    admin_keys = set(inspect.getsource(roster_directory_payload).split())
    for column in ("rosterId", "rosterActive", "canSignIn", "hasProfile"):
        assert any(column in token for token in admin_keys), column


def _body_without_docstring(func) -> str:
    tree = ast.parse(textwrap.dedent(inspect.getsource(func)))
    definition = tree.body[0]
    body = definition.body
    if (
        body
        and isinstance(body[0], ast.Expr)
        and isinstance(body[0].value, ast.Constant)
        and isinstance(body[0].value.value, str)
    ):
        body = body[1:]
    return chr(10).join(ast.unparse(node) for node in body)




def test_every_prefilled_profile_column_resolves_to_a_stage_the_registry_declares():
    from app.services.designers import PREFILL_MAP

    targets = oversight._prefill_targets()
    missing = [key for _column, key in PREFILL_MAP if key not in targets]
    assert missing == [], missing
    assert targets["designerName"][0] == "WORKSHOP_SETUP"
    assert targets["designerProfile"][0] == "WORKSHOP_PLAN_PARTICIPANTS_OPENING"


@pytest.mark.parametrize(
    "closed",
    ["PRE_SUBMISSION", "NEEDS_REVISION", "APPROVED", "SUBMITTED", "ARCHIVED"],
)
def test_a_workshop_whose_report_has_been_filed_refuses_a_designer_reassignment(closed):
    workshop = SimpleNamespace(id="w1", status=closed, createdById="c1")
    with pytest.raises(HTTPException) as exc:
        asyncio.run(oversight.reassign_designer(workshop, "d1", actor=user("MINISTRY_ADMIN")))
    assert exc.value.status_code == 422
    spoken = review_loop._LABELS[closed]
    assert spoken in exc.value.detail, exc.value.detail
    assert "_" not in exc.value.detail.split(",")[0], (
        f"the refusal shows a column name rather than a sentence: {exc.value.detail!r}"
    )
    assert "designer" in exc.value.detail


@pytest.mark.parametrize("open_status", ["DRAFT", "IN_PROGRESS", "COMPLETE"])
def test_the_three_states_nobody_outside_the_workshop_is_holding_are_not_refused(open_status):
    workshop = SimpleNamespace(id="w1", status=open_status, createdById="c1")
    with pytest.raises(Exception) as exc:
        asyncio.run(oversight.reassign_designer(workshop, "d1", actor=user("MINISTRY_ADMIN")))
    detail = getattr(exc.value, "detail", "")
    assert "re-attribute a filed document" not in str(detail), (
        f"{open_status} is being refused as a filed report; the guard has been widened past the "
        "states in which somebody outside the workshop is holding it"
    )


def test_the_closed_set_is_derived_from_the_review_loop_and_not_typed_out():
    assert oversight._CLOSED_STATUSES >= review_loop.UNDER_REVIEW
    still_open = {"DRAFT", "IN_PROGRESS", "COMPLETE"}
    assert frozenset(review_loop._LABELS) - still_open == oversight._CLOSED_STATUSES
    source = inspect.getsource(oversight)
    assert 'frozenset({"SUBMITTED", "ARCHIVED"})' not in source, (
        "the closed set has been re-typed as a literal; the next status rename will not reach it"
    )


def test_the_designer_prefill_never_falls_back_to_the_actor():
    source = _body_without_docstring(oversight.move_the_leads_profile_onto_the_workshop)
    assert "prefill_from_profile(user_id)" in source
    assert "or actor.id" not in source
    assert "actor.id or" not in source


def test_both_designer_write_paths_go_through_the_one_extracted_prefill():
    for door in (oversight.reassign_designer, oversight.set_named_designers):
        body = _body_without_docstring(door)
        assert "move_the_leads_profile_onto_the_workshop(" in body, door.__name__
        assert "prefill_from_profile(" not in body, (
            f"{door.__name__} has grown its own prefill call; there is one of these and it is "
            f"move_the_leads_profile_onto_the_workshop"
        )
        assert "save_stage(" not in body, (
            f"{door.__name__} has grown its own stage write; "
            f"tests/test_design_workshop_search_text.py fails on a third writer of that table"
        )


def test_the_designer_reassignment_merges_rather_than_replacing_stage_one():
    source = _body_without_docstring(oversight.move_the_leads_profile_onto_the_workshop)
    assert "merge=True" in source
    assert "replaceCollections=False" in source
    assert "submit=False" in source


def test_setting_both_capacities_at_once_is_one_transaction():
    source = _body_without_docstring(oversight.apply_oversight)
    assert "db.tx()" in source, (
        "apply_oversight writes both capacities one statement at a time with no transaction; "
        "a fault on the second leaves the first committed under a 500"
    )
    for write in ("delete_many", "upsert"):
        assert f"tx.designworkshopoversight.{write}" in source, (
            f"{write} is issued against the module singleton inside the transaction, so it commits "
            "on its own and a rollback leaves it behind — the exact failure the tx was opened for"
        )
    assert "db.designworkshopoversight.delete_many" not in source
    assert "db.designworkshopoversight.upsert" not in source


def test_the_validation_stays_outside_the_transaction_it_would_read_through():
    assert "db.tx()" not in inspect.getsource(oversight.assert_may_hold)
    route = inspect.getsource(routes.set_workshop_oversight)
    assert route.index("assert_may_hold") < route.index("apply_oversight")


def test_naming_the_officer_who_already_holds_a_capacity_writes_nothing():
    source = _body_without_docstring(oversight.apply_oversight)
    assert "current.userId == user_id" in source
    assert "continue" in source



@pytest.mark.parametrize(
    "closed",
    ["PRE_SUBMISSION", "NEEDS_REVISION", "APPROVED", "SUBMITTED", "ARCHIVED"],
)
def test_a_filed_report_refuses_the_whole_team_write_in_the_same_words_as_the_single_one(closed):
    workshop = SimpleNamespace(id="w1", status=closed, createdById="c1", designerName="A. Sharma")
    with pytest.raises(HTTPException) as plural:
        asyncio.run(
            oversight.set_named_designers(
                workshop, user_ids=["d1"], lead_user_id=None, actor=user("MINISTRY_ADMIN")
            )
        )
    with pytest.raises(HTTPException) as singular:
        asyncio.run(oversight.reassign_designer(workshop, "d1", actor=user("MINISTRY_ADMIN")))

    assert plural.value.status_code == 422
    assert plural.value.detail == singular.value.detail, (
        "the two designer doors word the same refusal differently, which is the drift the shared "
        "assert_the_report_is_not_filed exists to stop"
    )
    assert review_loop._LABELS[closed] in plural.value.detail


def test_the_whole_team_write_refuses_a_lead_who_is_not_on_the_workshop_before_it_reads_anything():
    workshop = SimpleNamespace(id="w1", status="IN_PROGRESS", createdById="c1", designerName="")
    with pytest.raises(HTTPException) as exc:
        asyncio.run(
            oversight.set_named_designers(
                workshop, user_ids=["d1", "d2"], lead_user_id="d3", actor=user("MINISTRY_ADMIN")
            )
        )
    assert exc.value.status_code == 422
    assert "Nothing was changed" in exc.value.detail
    assert "d3" not in exc.value.detail, "the refusal prints an account id at a ministry officer"


def test_the_whole_team_write_never_replaces_the_viewer_set_wholesale():
    source = _body_without_docstring(oversight.set_named_designers)
    assert "replace_viewers" not in source, (
        "the oversight feature replaces the viewer set wholesale; read attach_the_named_designers' "
        "docstring before allowing it"
    )
    assert "attach_the_named_designer(" in source
    assert "remove_one_viewer(" in source
    assert source.index("attach_the_named_designer(") < source.index("remove_one_viewer("), (
        "removals are issued before the adds, so a fault in the gap leaves the workshop with the "
        "outgoing designer gone and the incoming one not yet granted — a state only an ADMIN the "
        "assigner cannot escalate to could repair"
    )


def test_eligibility_is_asked_of_the_added_ids_and_never_of_the_removed_ones():
    source = _body_without_docstring(oversight.set_named_designers)
    assert "assert_every_designer_may_be_named(set(added)," in source
    assert source.index("assert_every_designer_may_be_named(") < source.index(
        "attach_the_named_designer("
    ), "validation runs after the first write, so one bad id leaves the good half applied"


def _viewer(user_id: str, name: str) -> dict[str, str]:
    return {"userId": user_id, "name": name, "email": f"{user_id}@x.test", "role": "DESIGNER"}


@pytest.fixture
def team(monkeypatch: pytest.MonkeyPatch):
    from app.services import designers as designer_service

    calls: dict[str, list] = {
        "added": [],
        "removed": [],
        "lead_moved": [],
        "validated": [],
        "name_cleared": [],
    }
    rows = [_viewer("lead-1", "A. Sharma"), _viewer("co-1", "B. Mohanty")]

    async def _viewer_rows(workshop_id):
        return list(rows)

    async def _prefill(user_id):
        return {"designerName": {"lead-1": "A. Sharma", "co-1": "B. Mohanty"}.get(user_id, "")}

    async def _attach(workshop_id, user_id, *, granted_by_id, creator_id):
        calls["added"].append(user_id)

    async def _remove(workshop_id, user_id):
        calls["removed"].append(user_id)

    async def _validate(user_ids, *, workshop_id=None, appointing=None):
        calls["validated"].append(set(user_ids))
        calls.setdefault("validated_context", []).append((workshop_id, appointing))

    async def _move_lead(workshop_id, user_id, *, actor):
        calls["lead_moved"].append(user_id)
        return ["WORKSHOP_SETUP"]

    async def _clear_name(workshop_id, *, actor):
        calls["name_cleared"].append(workshop_id)
        return ["WORKSHOP_SETUP"]

    async def _named(workshop):
        return []

    monkeypatch.setattr(oversight.design_workshop_viewers, "viewer_rows", _viewer_rows)
    monkeypatch.setattr(oversight.design_workshop_viewers, "remove_one_viewer", _remove)
    monkeypatch.setattr(oversight.design_workshops, "attach_the_named_designer", _attach)
    monkeypatch.setattr(oversight.design_workshops, "assert_every_designer_may_be_named", _validate)
    monkeypatch.setattr(designer_service, "prefill_from_profile", _prefill)
    monkeypatch.setattr(oversight, "move_the_leads_profile_onto_the_workshop", _move_lead)
    monkeypatch.setattr(oversight, "take_the_designers_name_off_the_workshop", _clear_name)
    monkeypatch.setattr(oversight, "named_designer_rows", _named)
    monkeypatch.setattr(
        oversight,
        "db",
        _Client(designworkshop=_Rows([SimpleNamespace(id="w1", designerName="A. Sharma")])),
    )
    return calls


def _save(user_ids, lead=None, *, designer_name="A. Sharma"):
    workshop = SimpleNamespace(
        id="w1", status="IN_PROGRESS", createdById="creator-1", designerName=designer_name
    )
    return asyncio.run(
        oversight.set_named_designers(
            workshop, user_ids=user_ids, lead_user_id=lead, actor=user("MINISTRY_ADMIN")
        )
    )


def test_taking_every_designer_off_a_workshop_removes_them_and_takes_the_name_off_too(team):
    answer = _save([])
    assert team["removed"] == ["lead-1", "co-1"], "the last designers kept their access"
    assert team["added"] == []
    assert team["name_cleared"] == ["w1"], (
        "the workshop is for nobody and its report still names a designer; the promoted column is "
        "re-promoted from stage 1 on the next save, so the field has to go in the same act"
    )
    assert team["lead_moved"] == [], "an empty set moved somebody's profile onto the workshop"
    assert answer["stagesWritten"] == ["WORKSHOP_SETUP"]
    assert [row["userId"] for row in answer["removedDesigners"]] == ["lead-1", "co-1"]


def test_a_workshop_that_names_nobody_is_not_written_to_when_the_set_is_already_empty(team):
    _save([], designer_name="")
    assert team["name_cleared"] == []
    assert team["lead_moved"] == []


def test_the_empty_set_clears_the_name_through_the_stage_and_never_through_the_column():
    body = _body_without_docstring(oversight.set_named_designers)
    assert "take_the_designers_name_off_the_workshop(" in body
    assert "db.designworkshop.update" not in body, (
        "the plural door writes the promoted column itself; the next stage-1 save re-promotes the "
        "name it just blanked"
    )
    assert "save_stage(" not in body, (
        "the plural door has grown its own stage write; there are two stage writers in this module "
        "and both are extracted functions"
    )


def test_the_name_is_taken_off_stage_one_with_a_write_the_registry_cannot_put_back():
    source = _body_without_docstring(oversight.take_the_designers_name_off_the_workshop)
    assert "merge=False" in source
    assert "submit=False" in source
    assert "replaceCollections=False" in source
    assert "save_stage(" in source
    assert "_prefill_targets()" in source, (
        "the stage and entity are hard-coded; the day the designer block moves, this blanks a field "
        "no form reads and the real one goes on promoting the old name"
    )


def test_dropping_the_lead_while_others_remain_is_refused_and_writes_nothing(team):
    with pytest.raises(HTTPException) as exc:
        _save(["co-1"])
    assert exc.value.status_code == 422
    assert "A. Sharma" in exc.value.detail, "the refusal must name who it is about"
    assert team["removed"] == []
    assert team["added"] == []
    assert team["name_cleared"] == []


def test_removing_a_co_designer_is_allowed_and_does_not_restamp_the_report(team):
    answer = _save(["lead-1"])
    assert team["removed"] == ["co-1"]
    assert team["added"] == []
    assert team["lead_moved"] == [], "a co-designer's removal moved the report's designer"
    assert answer["stagesWritten"] == []
    assert [row["userId"] for row in answer["removedDesigners"]] == ["co-1"], (
        "who lost access is not named in the answer; a silent stale grant was the whole defect"
    )


def test_adding_a_co_designer_validates_only_the_added_id_and_leaves_the_lead_alone(team):
    _save(["lead-1", "co-1", "new-1"])
    assert team["validated"] == [{"new-1"}]
    assert team["validated_context"] == [("w1", "u-MINISTRY_ADMIN")]
    assert team["added"] == ["new-1"]
    assert team["removed"] == []
    assert team["lead_moved"] == []


def test_moving_the_lead_is_the_one_thing_that_copies_a_profile_onto_the_workshop(team):
    answer = _save(["lead-1", "co-1"], lead="co-1")
    assert team["lead_moved"] == ["co-1"]
    assert answer["stagesWritten"] == ["WORKSHOP_SETUP"]
    assert team["removed"] == []


def test_the_creator_is_dropped_from_the_body_rather_than_refused(team):
    _save(["lead-1", "co-1", "creator-1"])
    assert team["added"] == [], "the creator was granted a redundant viewer row"
    assert team["removed"] == []


def test_the_first_designer_on_a_workshop_nobody_has_ever_led_is_promoted(team):
    _save(["lead-1", "co-1"], designer_name="")
    assert team["lead_moved"] == ["lead-1"]



def test_the_oversight_create_door_goes_through_the_shared_opener():
    source = _body_without_docstring(routes.open_workshop_from_oversight)
    assert "open_design_workshop(" in source
    assert "designworkshop.create" not in source
    assert "named_designer_team(" in source, (
        "the third door reads designerUserId beside designerUserIds itself; a third reading of "
        "that pair is how three doors come to disagree about whose name reaches the report"
    )
    assert "_parse_date(" in source, (
        "the third door parses dates its own way, so '2026-13-40' means one thing here and another "
        "on the ordinary create"
    )


def test_the_third_door_does_not_widen_the_creator_set_it_bypasses():
    assert set(deps.DESIGN_WORKSHOP_CREATOR_ROLES) == CREATORS
    assert not {"ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR"} & deps.DESIGN_WORKSHOP_CREATOR_ROLES

    created = [
        route
        for route in routes.router.routes
        if route.path.endswith("/workshops") and "POST" in route.methods
    ]
    assert len(created) == 1, "there is not exactly one create door on this prefix"
    gates = _dependency_names(created[0])
    assert "require_workshop_assigner" in gates
    assert "assert_can_create_design_workshops" not in gates

    assert "assert_can_create_design_workshops" not in _body_without_docstring(
        routes.open_workshop_from_oversight
    )


def test_the_staffing_filter_counts_a_blank_designer_name_as_unstaffed():
    source = inspect.getsource(routes.list_assignable_workshops)
    assert '{"designerName": None}, {"designerName": ""}' in source
    assert 'where.setdefault("AND", [])' in source, (
        "the staffing filter is written beside the search's OR; a typed term would then either "
        "stop narrowing or silently widen the search to every unstaffed workshop"
    )



def test_an_appointer_may_be_appointed_and_never_by_themselves():
    from app.services import design_workshop_posts as posts
    from app.services.design_workshop_inspectors import INSPECTION_HOLDER_ROLES, INSPECTION_ROLES

    assert oversight.OVERSIGHT_ASSIGNER_ROLES == ASSIGNERS
    assert set(INSPECTION_ROLES) == INSPECTABLE, "the inspector TIER is still the inspector tier"
    shared = oversight.OVERSIGHT_ASSIGNER_ROLES & INSPECTION_HOLDER_ROLES
    assert shared == frozenset(SERVING_ADMINS)
    assert frozenset(SERVING_ADMINS) == posts.SERVING_ADMIN_ROLES
    assert not {"ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR"} & INSPECTION_HOLDER_ROLES

    for post in ("ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR", "INSPECTOR", "DESIGNER"):
        refused = posts.separation_refusals(
            person="Me (me@x.test)", post=post, standing=posts.Standing(), self_appointed=True
        )
        assert len(refused) == 1 and "nobody appoints themselves" in refused[0], post



class _Users:
    def __init__(self, accounts):
        self.by_id = {account.id: account for account in accounts}

    async def find_many(self, where=None, **_kwargs):
        return [self.by_id[uid] for uid in where["id"]["in"] if uid in self.by_id]


class _OversightRows:
    def __init__(self, rows):
        self.rows = list(rows)

    async def find_many(self, where=None, **_kwargs):
        return [r for r in self.rows if r.designWorkshopId == where["designWorkshopId"]]


@pytest.fixture
def slots(monkeypatch: pytest.MonkeyPatch):
    from app.services import access_roster, design_workshop_posts as posts

    accounts = [user(role) for role in ALL_ROLES]

    def run(wanted, *, current=None, inspecting=(), authored=None, appointing=None):
        rows = [
            SimpleNamespace(designWorkshopId="w1", capacity=capacity, userId=holder)
            for capacity, holder in (current or {}).items()
        ]

        async def _barred(emails):
            return set()

        async def _authorship(workshop_id, user_ids):
            return {uid: frozenset(kinds) for uid, kinds in (authored or {}).items()}

        async def _posts(workshop_id, user_ids):
            return {uid: frozenset({posts.INSPECTOR}) for uid in inspecting}

        monkeypatch.setattr(
            oversight,
            "db",
            _Client(user=_Users(accounts), designworkshopoversight=_OversightRows(rows)),
        )
        monkeypatch.setattr(access_roster, "barred_among", _barred)
        monkeypatch.setattr(posts, "authorship_among", _authorship)
        monkeypatch.setattr(posts, "supervisory_posts_among", _posts)
        try:
            asyncio.run(oversight.assert_may_hold("w1", wanted, appointing=appointing))
        except HTTPException as refusal:
            return refusal
        return None

    return run


AD, RD = "ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR"


@pytest.mark.parametrize("role", sorted(SERVING_ADMINS))
def test_every_administrator_tier_may_be_named_to_either_slot(slots, role):
    assert slots({AD: f"u-{role}"}) is None
    assert slots({RD: f"u-{role}"}) is None


def test_a_directorate_post_is_still_refused_the_other_slot_with_a_422(slots):
    refusal = slots({AD: "u-REGIONAL_DIRECTOR"})
    assert refusal is not None and refusal.status_code == 422
    assert "a Ministry Admin, an admin or the master admin" in refusal.detail


def test_naming_yourself_to_a_slot_is_refused_and_keeping_a_slot_you_were_given_is_not(slots):
    refusal = slots({AD: "u-ADMIN"}, appointing="u-ADMIN")
    assert refusal is not None and refusal.status_code == 409
    assert "nobody appoints themselves" in refusal.detail
    assert slots({AD: "u-ADMIN", RD: "u-MINISTRY_ADMIN"}, current={AD: "u-ADMIN"},
                 appointing="u-ADMIN") is None


def test_one_person_in_both_slots_is_refused_once_however_it_arrives(slots):
    both = slots({AD: "u-ADMIN", RD: "u-ADMIN"})
    assert both is not None and both.status_code == 409
    assert both.detail.count("both the Assistant Director and the Regional Director") == 1, (
        "the same fact is said once, not once per slot"
    )
    later = slots({RD: "u-ADMIN"}, current={AD: "u-ADMIN"})
    assert later is not None and later.status_code == 409


def test_a_swap_between_the_slots_is_judged_on_where_it_lands(slots):
    assert slots({AD: "u-MINISTRY_ADMIN", RD: "u-ADMIN"}, current={AD: "u-ADMIN"}) is None
    assert slots({AD: None, RD: "u-ADMIN"}, current={AD: "u-ADMIN"}) is None


def test_the_workshops_inspector_cannot_be_its_supervisor(slots):
    refusal = slots({RD: "u-MASTER_ADMIN"}, inspecting={"u-MASTER_ADMIN"})
    assert refusal is not None and refusal.status_code == 409
    assert "inspects this workshop" in refusal.detail


@pytest.mark.parametrize(
    ("evidence", "phrase"),
    [
        ({"DESIGNER_ACCESS"}, "holds designer access to this workshop"),
        ({"STAGE_WRITES"}, "has written this workshop's stages"),
    ],
)
def test_an_author_cannot_supervise_the_workshop(slots, evidence, phrase):
    refusal = slots({AD: "u-ASSISTANT_DIRECTOR"}, authored={"u-ASSISTANT_DIRECTOR": evidence})
    assert refusal is not None and refusal.status_code == 409
    assert phrase in refusal.detail
    assert "Creating a workshop is not authoring it" in refusal.detail


def test_an_account_problem_and_a_staffing_problem_arrive_together_as_a_422(slots):
    refusal = slots({AD: "u-DESIGNER", RD: "u-ADMIN"}, inspecting={"u-ADMIN"})
    assert refusal is not None and refusal.status_code == 422
    assert "DESIGNER" in refusal.detail
    assert "inspects this workshop" in refusal.detail


def test_the_inspector_routes_import_the_assigner_door_rather_than_declaring_a_second_one():
    from app.api.routes import design_workshop_inspections as inspections

    assert inspections.require_workshop_assigner is routes.require_workshop_assigner
    assert "require_admin" not in inspect.getsource(inspections.set_inspectors)



_OVERSIGHT_FILES = (
    "app/services/design_workshop_oversight.py",
    "app/api/routes/design_workshop_oversight.py",
    "app/services/artisan_import.py",
)


def test_the_oversight_feature_writes_no_viewer_row_except_through_the_named_designer_helper():
    backend = Path(__file__).resolve().parents[1]
    offenders: list[str] = []
    for rel in _OVERSIGHT_FILES:
        tree = ast.parse((backend / rel).read_text(encoding="utf-8"))
        for node in ast.walk(tree):
            if (
                isinstance(node, ast.Call)
                and isinstance(node.func, ast.Attribute)
                and isinstance(node.func.value, ast.Attribute)
                and node.func.value.attr == "designworkshopviewer"
            ):
                offenders.append(f"{rel}:{node.lineno}")
    assert offenders == [], (
        "the oversight feature writes or reads DesignWorkshopViewer directly:\n  "
        + "\n  ".join(offenders)
        + "\nRead the header of app/services/design_workshop_oversight.py before allowing it."
    )


def test_the_sweep_actually_reaches_the_files_it_is_defending():
    backend = Path(__file__).resolve().parents[1]
    for rel in _OVERSIGHT_FILES:
        assert (backend / rel).exists(), rel
