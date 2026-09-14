"""The sixth scope's gates, its shape, and the two things about it that would be silent.

**NO DATABASE.** Every predicate here is either pure or reads a delegate narrow enough that an
unexpected clause fails loudly — the device ``test_dw_inspector_scope_gate`` uses one scope over.
The DB-backed behaviour of the write path (two officers, one workshop, a concurrent PUT) belongs in
an integration test; what is asserted here is the set of properties that would otherwise be true
only by accident.

Every test is named as the sentence it asserts.
"""

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

#: Every tier on the ladder, read off ``ROLE_RANK`` rather than typed out.
#:
#: **READ RATHER THAN LISTED, SO A TWELFTH TIER CANNOT LAND WITH THIS FILE SILENTLY TESTING ELEVEN
#: ROLES AND PASSING.** The parity sweep in ``test_role_ladder_parity`` exists because a hand-typed
#: mirror of this dict is a mirror that goes stale; the same argument applies to a test's own list.
ALL_ROLES = tuple(deps.ROLE_RANK)

OFFICERS = {"ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR", "MINISTRY_ADMIN"}
ASSIGNERS = {"MINISTRY_ADMIN", "ADMIN", "MASTER_ADMIN"}


def user(role: str, **extra):
    return SimpleNamespace(
        id=f"u-{role}", name=role.title(), email=f"{role}@x.test", role=role, **extra
    )


# --------------------------------------------------------------------------------------
# 1. THE TWO PREDICATES, OVER EVERY TIER
# --------------------------------------------------------------------------------------


def test_the_ladder_has_the_three_directorate_tiers_this_feature_is_built_on():
    """The control for everything below. Without it a renamed tier would make every set-membership
    assertion in this file pass by admitting nobody at all."""
    assert set(ALL_ROLES) >= OFFICERS, sorted(OFFICERS - set(ALL_ROLES))
    assert deps.ROLE_RANK["ASSISTANT_DIRECTOR"] > deps.ROLE_RANK["PROFESSOR"]
    assert deps.ROLE_RANK["REGIONAL_DIRECTOR"] > deps.ROLE_RANK["ASSISTANT_DIRECTOR"]
    assert deps.ROLE_RANK["MINISTRY_ADMIN"] > deps.ROLE_RANK["REGIONAL_DIRECTOR"]


@pytest.mark.parametrize("role", ALL_ROLES)
def test_exactly_the_three_ministry_posts_are_officers(role):
    assert oversight.is_officer(user(role)) is (role in OFFICERS), role


@pytest.mark.parametrize("role", ALL_ROLES)
def test_exactly_a_ministry_admin_and_the_two_platform_tiers_may_assign(role):
    """**A REGIONAL DIRECTOR IS REFUSED HERE AND THEY OUTRANK AN ASSISTANT DIRECTOR.**

    Every rank instinct is wrong about this row. "The supervised must not choose the supervisor" is
    the same rule the inspection tier states one rung down; an RD who should be able to assign is a
    MINISTRY_ADMIN, which is a role change an admin makes on /users and not a widening here.
    """
    assert oversight.can_assign_workshop_oversight(user(role)) is (role in ASSIGNERS), role


def test_a_regional_director_is_an_officer_and_is_not_an_assigner():
    """Said once more as a plain sentence, because the parametrised pair above reads as symmetry
    and this asymmetry is the whole design."""
    rd = user("REGIONAL_DIRECTOR")
    assert oversight.is_officer(rd) is True
    assert oversight.can_assign_workshop_oversight(rd) is False


def test_neither_predicate_admits_a_missing_user():
    """Fail-closed. Both are written against the role STRING rather than against ROLE_RANK, so a
    deployment where the tiers are not on the ladder answers False for everybody rather than
    raising."""
    assert oversight.is_officer(None) is False
    assert oversight.can_assign_workshop_oversight(None) is False
    assert oversight.is_officer(SimpleNamespace(role=None)) is False


def test_an_admin_is_refused_the_officers_own_surface_and_is_told_which_door_they_want():
    """Admitting admins here would mean an empty page they read as a broken deployment, or a second
    full read of every workshop in the repository. So it is a 403 that names the other door."""
    with pytest.raises(HTTPException) as exc:
        oversight.assert_oversight_surface(user("ADMIN"))
    assert exc.value.status_code == 403
    assert "/api/design-workshops" in exc.value.detail
    assert "Ministry Admin" in exc.value.detail
    # The control: an officer is not refused.
    oversight.assert_oversight_surface(user("ASSISTANT_DIRECTOR"))


def test_the_assigner_refusal_names_the_screen_an_officer_should_use_instead():
    with pytest.raises(HTTPException) as exc:
        oversight.assert_may_assign_oversight(user("REGIONAL_DIRECTOR"))
    assert exc.value.status_code == 403
    assert "Workshops I monitor" in exc.value.detail
    oversight.assert_may_assign_oversight(user("MINISTRY_ADMIN"))


# --------------------------------------------------------------------------------------
# 2. THE CAPACITIES
# --------------------------------------------------------------------------------------


def test_one_role_per_capacity_and_a_ministry_admin_fits_neither():
    """A flat set would let an officer be filed in the other's slot, which prints the wrong post
    beside the right name on a document going to a ministry.

    MINISTRY_ADMIN is in the DIRECTORY and in NEITHER SLOT: the directory answers "who are the
    officers", and the requirement names exactly two posts per workshop.
    """
    assert oversight.CAPACITIES == ("ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR")
    assert oversight.capacities_for("ASSISTANT_DIRECTOR") == ["ASSISTANT_DIRECTOR"]
    assert oversight.capacities_for("REGIONAL_DIRECTOR") == ["REGIONAL_DIRECTOR"]
    assert oversight.capacities_for("MINISTRY_ADMIN") == []
    assert oversight.capacities_for("ADMIN") == []


def test_every_capacity_the_enum_declares_has_a_role_that_may_hold_it():
    """The import-time guard, asserted again where a reader will find it.

    A capacity Postgres can store and this module cannot validate is a row on a workshop that
    neither the AD lookup nor the RD lookup finds — an officer assigned to a workshop nobody can see
    they were assigned to, which is the exact failure the enum was chosen to prevent.
    """
    try:
        from prisma.enums import DwOversightCapacity
    except ImportError:  # pragma: no cover - a client older than the migration
        pytest.skip("the generated Prisma client predates DwOversightCapacity")
    assert {m.value for m in DwOversightCapacity} == set(oversight.OVERSIGHT_CAPACITY_ROLES)


# --------------------------------------------------------------------------------------
# 3. THE SCOPE CLAUSE AND THE READ-ONLY LOADER
# --------------------------------------------------------------------------------------


def test_the_scope_clause_reads_its_own_relation_and_has_no_creator_arm():
    """An officer creates nothing, so this clause has ONE source and no fallback.

    It must also never read ``viewers``: a viewer row confers every stage save, and a scope that
    consulted it would hand an officer stage WRITES on a fortnight of somebody else's fieldwork.
    """
    clause = oversight.oversight_by_clause("u1")
    assert clause == {"oversight": {"some": {"userId": "u1"}}}
    assert "viewers" not in repr(clause)
    assert "createdById" not in repr(clause)


def test_the_read_only_loader_has_no_for_edit_parameter():
    """**THE STRUCTURAL PROPERTY, AS ONE LINE.**

    ``design_workshops.load_workshop_or_404`` takes ``for_edit``. The officer's loader is a
    different function that cannot express the same thing: there is no argument an officer's request
    could carry that turns its read into a write, because there is no such argument.

    The control below is not decoration — without it this assertion would pass just as happily if
    somebody deleted ``for_edit`` from the sibling.
    """
    from app.services import design_workshops as workshop_service

    ours = inspect.signature(oversight.load_overseen_workshop_or_404).parameters
    assert "for_edit" not in ours
    theirs = inspect.signature(workshop_service.load_workshop_or_404).parameters
    assert "for_edit" in theirs, "the control: the sibling loader is still the one that edits"


class _Rows:
    """A delegate over a list, narrow enough that an unexpected clause fails loudly."""

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
    """Belt and braces on purpose: this is the function a future caller will reach for, and a loader
    that trusts its caller's gate is how a scope leaks onto a surface nobody re-read."""
    with pytest.raises(HTTPException) as exc:
        asyncio.run(
            oversight.load_overseen_workshop_or_404(
                WORKSHOP_ID, SimpleNamespace(id=OFFICER_ID, role="ADMIN")
            )
        )
    assert exc.value.status_code == 404, "404 and not 403 — see the loader's docstring"
    assert exc.value.detail == "Record not found"


def test_a_workshop_out_of_scope_is_a_404_that_reads_like_any_other(scoped):
    """A 403 would confirm the id exists to exactly the people this is turning away."""
    with pytest.raises(HTTPException) as exc:
        asyncio.run(
            oversight.load_overseen_workshop_or_404(
                WORKSHOP_ID, SimpleNamespace(id=STRANGER_ID, role="REGIONAL_DIRECTOR")
            )
        )
    assert (exc.value.status_code, exc.value.detail) == (404, "Record not found")


def test_a_soft_deleted_workshop_is_a_404_with_no_409_arm(scoped):
    """The designer's loader answers 409 so somebody holding unsent stages is told to ask for a
    restore. An officer has nothing pending and no restore button."""
    scoped.designworkshop.rows = [SimpleNamespace(id=WORKSHOP_ID, deletedAt=object())]
    with pytest.raises(HTTPException) as exc:
        asyncio.run(
            oversight.load_overseen_workshop_or_404(
                WORKSHOP_ID, SimpleNamespace(id=OFFICER_ID, role="ASSISTANT_DIRECTOR")
            )
        )
    assert exc.value.status_code == 404


# --------------------------------------------------------------------------------------
# 4. THE ROUTER'S SHAPE
# --------------------------------------------------------------------------------------


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
    """A route added here without a ``Depends`` is visible as a missing dependency rather than as a
    missing line in a body."""
    for route in routes.router.routes:
        gates = _dependency_names(route)
        assert gates & {"require_officer", "require_workshop_assigner"}, (
            f"{route.path} is on the oversight router with neither door"
        )


def test_every_route_an_officer_can_reach_is_a_get():
    """**THE ASSERTION THAT SURVIVES A NEW ROUTE.**

    The parametrised refusals cover the doors that exist today; this one fails when somebody adds a
    POST to this router and hangs it on the officer's dependency — which is how a read-only surface
    acquires its first write.
    """
    for route in routes.router.routes:
        if "require_officer" in _dependency_names(route):
            assert set(route.methods) == {"GET"}, (
                f"{route.methods} {route.path} is reachable by an officer and is not a GET. Read "
                f"the header of app/services/design_workshop_oversight.py before changing this "
                f"test: read-only here is structural, not a policy note."
            )


def test_every_literal_path_is_declared_before_the_workshop_id_route():
    """``{workshop_id}`` matches ``officers`` perfectly well and would answer 404 "Record not found".

    **THAT HAS ALREADY SHIPPED ONCE ON A LIVE SERVER AS A PERMANENTLY EMPTY ADMIN PICKER** — a 404
    that reads as "nothing here" rather than as "your route never ran". FastAPI matches in
    declaration order, so this asserts the order.
    """
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
    """``/design-workshop-oversight`` and ``/design-workshops`` are DIFFERENT prefixes.

    Starlette matches the whole path, so nothing this router declares can be swallowed by the other
    one however the two are registered — which is why ``api/router.py``'s comment says its position
    is stylistic. Pinned so that nobody "fixes" a 404 on this prefix by reordering that file.
    """
    assert routes.router.prefix == "/design-workshop-oversight"
    for route in routes.router.routes:
        assert not route.path.startswith("/design-workshops/")


# --------------------------------------------------------------------------------------
# 5. THE UPLOAD DOOR
# --------------------------------------------------------------------------------------


class _Upload:
    """The two things ``_read_artisan_upload`` reads off a multipart part."""

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
    """The cheapest refusal first: a string on the multipart part, no body copied into the heap."""
    upload = _Upload("roster.docx", b"PK\x03\x04" + b"x" * 100)
    with pytest.raises(HTTPException) as exc:
        asyncio.run(routes._read_artisan_upload(upload))
    assert exc.value.status_code == 415
    assert "artisan list pro-forma" in exc.value.detail
    assert "File > Save As" in exc.value.detail
    assert upload._at == 0, "the body must not have been read"


def test_a_part_with_no_filename_falls_through_to_the_magic_byte_check():
    """The extension gate is ``if name and not name.endswith(...)`` — NOT total.

    A nameless part skips it entirely and reaches the parser, whose magic-byte branch describes the
    FILE rather than the label somebody put on it. Stated as a test because a reader who assumed
    the gate was total would delete that branch as redundant.
    """
    content = asyncio.run(routes._read_artisan_upload(_Upload(None, b"%PDF-1.7\n")))
    assert content == b"%PDF-1.7\n", "no 415 — the extension gate did not fire"


def test_an_empty_upload_is_a_422_naming_the_pro_forma():
    with pytest.raises(HTTPException) as exc:
        asyncio.run(routes._read_artisan_upload(_Upload("roster.xlsx", b"")))
    assert exc.value.status_code == 422
    assert "artisan list pro-forma" in exc.value.detail


def test_an_oversized_artisan_upload_is_refused_at_four_megabytes_not_eight():
    """The two ceilings are INDEPENDENT and must stay that way.

    A thousand-question instrument with every sitting's answers in it is genuinely large; fifteen
    artisans are not. This asserts the number actually applied AND that the questionnaire's is
    untouched — a shared constant would make one of the two wrong the first time either moved.
    """
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
    """Refusing a caller who did what the error message told them to is the one failure mode a size
    gate must not have."""
    at_limit = b"x" * routes.MAX_ARTISAN_UPLOAD_BYTES
    content = asyncio.run(routes._read_artisan_upload(_Upload("roster.xlsx", at_limit)))
    assert len(content) == routes.MAX_ARTISAN_UPLOAD_BYTES


def test_the_upload_door_calls_the_shared_bound_rather_than_counting_bytes_itself():
    """``services/uploads.read_upload_bounded`` already bounds the heap, already skips the pointless
    copy on an honest ``Content-Length`` and already keeps the peak residency inside one chunk —
    all of it covered by ``tests/test_upload_bounds.py``. A second implementation is a second
    discipline to drift.
    """
    source = inspect.getsource(routes._read_artisan_upload)
    assert "read_upload_bounded(" in source
    assert "while True" not in source, "a hand-rolled read loop is a second bound"


# --------------------------------------------------------------------------------------
# 6. THE OFFICER'S DESIGNER PICKER CARRIES NO ROSTER COLUMNS
# --------------------------------------------------------------------------------------


def test_the_oversight_designer_picker_carries_no_roster_columns():
    """**THE KEY SET, NOT A SAMPLE**, and that is the whole point of the assertion.

    A later change that adds a field to a SHARED payload builder "for symmetry" would hand a
    Ministry Admin the empanelment standing of every designer in the repository: a field appears,
    nothing refuses it, and the control the separate gate exists to keep is gone with no test red.
    Asserting the exact key set is what notices.
    """
    from app.services.designers import assignable_designers_payload, roster_directory_payload

    rows = [SimpleNamespace(id="d1", name="A Designer", email="d@x.test", role="DESIGNER")]
    [payload] = assignable_designers_payload(rows)
    assert set(payload) == {"id", "name", "email", "role"}

    # The control: the ADMIN's payload is a different function and does carry the roster columns,
    # so the assertion above is about a deliberate split rather than about a payload nobody built.
    assert roster_directory_payload is not assignable_designers_payload
    admin_keys = set(inspect.getsource(roster_directory_payload).split())
    for column in ("rosterId", "rosterActive", "canSignIn", "hasProfile"):
        assert any(column in token for token in admin_keys), column


# --------------------------------------------------------------------------------------
# 7. THE DESIGNER ARM
# --------------------------------------------------------------------------------------

def _body_without_docstring(func) -> str:
    """The CODE of a function, with its docstring removed.

    Load-bearing rather than tidy: these two tests forbid a STRING that both docstrings quote in
    order to explain why it is forbidden. Reading the raw source would make the explanation itself
    the failure — and the obvious "fix" would be deleting the paragraph that says why the rule
    exists, which is the worst possible outcome for a rule nothing else enforces.
    """
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
    """The stage/entity split is DERIVED from the registry, never hand-typed.

    A hand-typed split of ``PREFILL_MAP`` would be a second copy of a table
    ``tests/test_designer_prefill_contract.py`` already tests for drift, and the way it would fail
    is a profile column that silently stops reaching a report when somebody moves a field between
    stages.
    """
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
    """A filed report NAMES a designer. Silently swapping it re-attributes a filed document.

    **ALL FIVE, AND THE FIRST TWO ARE WHY THIS IS PARAMETRISED.** This test covered SUBMITTED and
    ARCHIVED alone, which was the whole of "filed" under the five-token vocabulary this product had
    until 2026-09-13. That wave redefined SUBMITTED to mean "the APPROVED report has been handed
    on" — reachable only through an approvals router that is not built — and made PRE_SUBMISSION
    the designer's hand-in. So the guard was protecting a state nothing could reach while the two
    states in which officers are actually holding the report walked straight through it: a report
    under inspection had its authorship, its stage-1 header and its promoted ``designerName``
    rewritten under the officers reading it, and on a sent-back report the reassignment's own
    ``save_stage`` calls tripped the ``NEEDS_REVISION and _content_changed`` arm — silently
    RESUBMITTING the report, spending a submission round and erasing which officer sent it back.

    THE REFUSAL SPEAKS THE STATUS RATHER THAN PRINTING THE COLUMN NAME. ``.title()`` stood here and
    rendered the token that matters most as "Pre_Submission".
    """
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
    """The other half of the set, so the guard cannot be "fixed" into refusing everything.

    A workshop nobody has handed in is exactly what this route is FOR — the ministry naming a
    designer on a planned or half-filled workshop. These reach the write (and fail on the absent
    database here, which is the point: they got past the status check).
    """
    workshop = SimpleNamespace(id="w1", status=open_status, createdById="c1")
    with pytest.raises(Exception) as exc:
        asyncio.run(oversight.reassign_designer(workshop, "d1", actor=user("MINISTRY_ADMIN")))
    detail = getattr(exc.value, "detail", "")
    assert "re-attribute a filed document" not in str(detail), (
        f"{open_status} is being refused as a filed report; the guard has been widened past the "
        "states in which somebody outside the workshop is holding it"
    )


def test_the_closed_set_is_derived_from_the_review_loop_and_not_typed_out():
    """**THE REASON THE PREVIOUS DEFECT WAS POSSIBLE, CLOSED AT THE SOURCE.**

    The set was a literal, so a rename in the review loop could not reach it and did not. Derived
    from ``UNDER_REVIEW`` plus the three terminal tokens, a ninth status cannot be added to the
    graph without this set moving with it — and the complement below is the sentence that says what
    the rule MEANS, so a future reader can check the derivation against the intent rather than
    against itself.
    """
    assert oversight._CLOSED_STATUSES >= review_loop.UNDER_REVIEW
    still_open = {"DRAFT", "IN_PROGRESS", "COMPLETE"}
    assert frozenset(review_loop._LABELS) - still_open == oversight._CLOSED_STATUSES
    source = inspect.getsource(oversight)
    assert 'frozenset({"SUBMITTED", "ARCHIVED"})' not in source, (
        "the closed set has been re-typed as a literal; the next status rename will not reach it"
    )


def test_the_designer_prefill_never_falls_back_to_the_actor():
    """**THE DEFECT ``seed_designer_prefill``'S DOCSTRING NAMES**, asserted at the source.

    ``prefill_from_profile(designer_id or actor.id)`` is one plausible extra word, and what it
    produces is an administrator who picked a designer off a list and got their OWN name into a
    ministry report — with completeness scoring 100% and the only detector being a human reading the
    cover. Read at the source because the runtime path needs a database and this does not.
    """
    source = _body_without_docstring(oversight.reassign_designer)
    assert "prefill_from_profile(user_id)" in source
    assert "or actor.id" not in source
    assert "actor.id or" not in source


def test_the_designer_reassignment_merges_rather_than_replacing_stage_one():
    """``merge=True`` and ``replaceCollections=False``, read at the source.

    A wholesale replace would delete every other field the designer typed into stage 1 — and
    ``_coerce_promoted`` nulls a promoted column whose contributing entity came back blank, so the
    craft name, the cluster, the state, the district and both dates would go with it, under a 200
    reading "Stage saved".
    """
    source = _body_without_docstring(oversight.reassign_designer)
    assert "merge=True" in source
    assert "replaceCollections=False" in source
    assert "submit=False" in source


def test_setting_both_capacities_at_once_is_one_transaction():
    """**A HALF-APPLIED ASSIGNMENT IS A SILENT ACCESS CHANGE UNDER A 500.**

    ``wanted`` carries BOTH capacities whenever a client sends both — the schema's own docstring
    says "THE TWO TRAVEL TOGETHER" and the route builds the dict from ``model_fields_set`` — so the
    loop routinely issues two independent statements. Statement by statement against the module
    singleton each one autocommits, so an AD swap that lands followed by an RD create that raises
    (the account hard-deleted between ``assert_may_hold`` and the write and the ``userId`` FK
    refusing it, a dropped connection, a concurrent save colliding on the ``(designWorkshopId,
    capacity)`` primary key) answers the administrator a 500 saying nothing happened while the AD
    has in fact changed and committed.

    AND AN OVERSIGHT ROW IS THE READ SCOPE. ``load_overseen_workshop_or_404`` is built from it, so
    the officer named in the half that landed silently gained read access to every stage of the
    workshop and the officer they replaced silently lost it — with the screen still showing the pair
    it had before.

    Read at the source: the failure is a rollback that does not happen, which no happy-path test can
    see, and the DB-backed version would have to inject a driver fault at the second statement.
    """
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
    """``assert_may_hold`` reads accounts and rosters through the module singleton.

    ``attach_the_named_designer``'s docstring sets out what a read like that costs when it is issued
    from inside a transaction that has just written the rows it is reading: it sees nothing and
    refuses everything. The route's order — validate to completion, then write — is what keeps them
    apart, and this is the assertion that keeps somebody from "tidying" the validation into the
    transaction along with the writes.
    """
    assert "db.tx()" not in inspect.getsource(oversight.assert_may_hold)
    route = inspect.getsource(routes.set_workshop_oversight)
    assert route.index("assert_may_hold") < route.index("apply_oversight")


def test_naming_the_officer_who_already_holds_a_capacity_writes_nothing():
    """IDEMPOTENT, and ``assignedAt`` is the reason.

    That timestamp is the only answer anybody has to "how long has this workshop been supervised",
    so re-saving an unchanged screen must not restamp it. Asserted against the branch rather than
    against a database: the ``continue`` is what stands between a no-op save and a rewritten
    supervision history.
    """
    source = _body_without_docstring(oversight.apply_oversight)
    assert "current.userId == user_id" in source
    assert "continue" in source


# --------------------------------------------------------------------------------------
# 8. THE SIXTH SCOPE DOES NOT WRITE THE FIFTH'S TABLE, OR THE VIEWERS'
# --------------------------------------------------------------------------------------


_OVERSIGHT_FILES = (
    "app/services/design_workshop_oversight.py",
    "app/api/routes/design_workshop_oversight.py",
    "app/services/artisan_import.py",
)


def test_the_oversight_feature_writes_no_viewer_row_except_through_the_named_designer_helper():
    """**THE ONE MISTAKE IN THIS FEATURE THAT WOULD BE SILENT.**

    ``load_workshop_or_404(for_edit=True)`` carries no role check of its own beyond the grant arm's
    role gate; a viewer row confers every stage save, the AI-layer verbs, the dictation consent and
    the export ledger. An officer written into that table would have more power than anyone intended
    and every screen would look correct.

    The ONE permitted write is ``attach_the_named_designer``, and the account it names is a
    DESIGNER. An AST walk rather than a grep, because the thing being forbidden is a CALL.
    """
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
    """A sweep that walks nothing passes for ever."""
    backend = Path(__file__).resolve().parents[1]
    for rel in _OVERSIGHT_FILES:
        assert (backend / rel).exists(), rel
