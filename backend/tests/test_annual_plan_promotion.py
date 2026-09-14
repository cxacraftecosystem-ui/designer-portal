"""Turning one planned row into a real workshop — and the silent ways that loses its header.

The contract tests in section 1 need nothing. The behavioural tests in section 2 need Postgres:

    docker compose up -d postgres
    cd backend && .venv/Scripts/python.exe -m prisma migrate deploy --schema prisma/schema.prisma

══ THE DEFECT EVERY TEST IN THIS FILE IS ABOUT ═══════════════════════════════════════════════════

``stage_schema.PROMOTED_COLUMNS`` says which stage-1 fields are copied onto ``DesignWorkshop``
COLUMNS. The stage entry is the writer; the column is the index. A promoted column written with NO
STAGE ENTRY BEHIND IT is nulled by the designer's first stage-1 save, under a 200 reading "Stage
saved": ``touched_entities`` gains ``workshopSetup`` for any entry naming it, the web sends a read
stage's singleton whether or not it holds anything, and ``_coerce_promoted`` nulls a promoted column
of a touched entity whose value is blank.

Nothing warns. Completeness does not move. The workshop loses its craft, cluster, state, district,
venue and dates, becomes invisible to every list filter on those axes, and shows "—" in the list's
own columns for the whole fortnight of capture.

``promote_entry``'s ``seeded`` map is a SECOND hand-written join between a data source and the
registry's field keys — the first being ``designers.PREFILL_MAP``, which
``tests/test_designer_prefill_contract.py`` guards and which imports only that map, so it cannot see
this one. Section 1 is that guard, for this map.
"""

import ast
import inspect
import textwrap
import uuid
from datetime import UTC, datetime
from typing import Any

import pytest
from conftest import needs_db

from app.core.db import db
from app.core.security import create_access_token, hash_password
from app.schemas.annual_plan import AnnualPlanPromoteRequest
from app.schemas.design_workshop_viewers import MAX_DESIGN_WORKSHOP_VIEWERS
from app.schemas.design_workshops import DesignWorkshopCreate
from app.services import annual_plan
from app.services.annual_plan_xlsx import _CAP_MIRRORS, _TEXT_CAPS
from app.services.stage_schema import PROMOTED_COLUMNS, stages

pytestmark = pytest.mark.anyio

PASSWORD = "annual-plan-promotion-password"


def _literal_keys(source: str, name: str) -> list[str]:
    """The first element of every pair in a ``{name} = {...}`` comprehension over a tuple of tuples.

    Read with ``ast`` rather than with a regular expression, so that a comment mentioning a key, or
    a key spelled across two lines, cannot make this test agree with something the code does not do.
    """
    tree = ast.parse(inspect.cleandoc(source))
    found: list[str] = []

    def _collect(value: ast.expr) -> None:
        for literal in ast.walk(value):
            if isinstance(literal, ast.Tuple) and literal.elts:
                first = literal.elts[0]
                if isinstance(first, ast.Constant) and isinstance(first.value, str):
                    found.append(first.value)

    class _Visitor(ast.NodeVisitor):
        def visit_Assign(self, node: ast.Assign) -> None:
            if name in [t.id for t in node.targets if isinstance(t, ast.Name)]:
                _collect(node.value)
            self.generic_visit(node)

        # ``columns: dict[str, Any] = {...}`` IS AN ``AnnAssign`` AND NOT AN ``Assign``. Missing it
        # made this reader answer an empty list — and an empty list makes every "every key is …"
        # assertion below vacuously true, which is the one failure mode a contract test must not
        # have. The `assert passed` / `assert SEEDED_KEYS` lines are what caught it.
        def visit_AnnAssign(self, node: ast.AnnAssign) -> None:
            if isinstance(node.target, ast.Name) and node.target.id == name and node.value:
                _collect(node.value)
            self.generic_visit(node)

    _Visitor().visit(tree)
    return found


def _workshop_setup_fields() -> dict[str, Any]:
    for spec in stages():
        for entity in spec.entities:
            if entity.key == "workshopSetup":
                return {f.key: f for f in entity.fields}
    raise AssertionError("the registry has no workshopSetup entity")


SEEDED_KEYS = _literal_keys(inspect.getsource(annual_plan.promote_entry), "seeded")
COLUMN_KEYS = _literal_keys(inspect.getsource(annual_plan.promote_entry), "columns")


# --------------------------------------------------------------------------------------
# 1. The two hand-written joins, guarded. No database.
# --------------------------------------------------------------------------------------


def test_every_promoted_key_promote_entry_seeds_is_a_real_workshop_setup_field():
    """TWO SILENT FAILURES AT ONCE, and neither leaves a trace anywhere else.

    A key that does not exist on the entity is written into the stage JSON FOR EVER and rendered by
    nothing; a key that exists but is deprecated is written into a field no form shows. Both look
    exactly like success from the outside — the promotion returns 201 and the workshop appears.
    """
    fields = _workshop_setup_fields()
    assert SEEDED_KEYS, "the seeded map must be readable — has it been rewritten?"
    for key in SEEDED_KEYS:
        assert key in fields, f"`{key}` is not a workshopSetup field"
        assert not fields[key].deprecated, f"`{key}` is deprecated"


def test_every_promoted_key_is_a_column_the_registry_actually_promotes():
    """The other half: a key the registry does NOT promote is seeded into a stage nobody reads it
    from as a header, so the workshop's own row never carries it and every list filter misses it."""
    promoted = {
        path.split(".", 1)[1] for path in PROMOTED_COLUMNS if path.startswith("workshopSetup.")
    }
    for key in SEEDED_KEYS:
        assert key in promoted, f"`{key}` is seeded but is not in PROMOTED_COLUMNS"


def test_no_parser_clip_cap_exceeds_the_field_it_is_promoted_into():
    """THE PAIR CANNOT DRIFT IN EITHER DIRECTION, and the reason is a hole rather than an oversight.

    ``seed_designer_prefill`` runs ``validate_entry(..., enforce_required=False)``, and a value that
    validator REFUSES is DROPPED AND ONLY LOGGED — "a value refused here vanished completely". The
    promoted COLUMN is still written. So a parser cap one character looser than its FieldSpec writes
    a column with no stage entry behind it, which is exactly the state the first stage-1 save nulls.
    """
    fields = _workshop_setup_fields()
    assert _CAP_MIRRORS, "the mirror map must not be empty"
    for role, field_key in _CAP_MIRRORS.items():
        assert field_key in fields, f"`{field_key}` is not a workshopSetup field"
        assert _TEXT_CAPS[role] <= fields[field_key].max_length, (
            f"parser cap for {role} ({_TEXT_CAPS[role]}) exceeds "
            f"workshopSetup.{field_key} ({fields[field_key].max_length})"
        )


def test_every_seeded_role_that_is_promoted_has_a_cap_mirror():
    """A promoted TEXT value with no entry in ``_CAP_MIRRORS`` is a value nothing bounds.

    Date and enum values are exempt — they are not clipped and cannot be over-length — so the
    exemption is spelled by TYPE and not by a hand-kept list of names.
    """
    fields = _workshop_setup_fields()
    bounded = {key for key in SEEDED_KEYS if fields[key].max_length}
    mirrored = set(_CAP_MIRRORS.values())
    assert bounded <= mirrored, f"unbounded promoted text: {sorted(bounded - mirrored)}"


def test_every_column_promote_entry_passes_is_a_header_column_and_none_is_stage_one_owned():
    """THE OTHER HALF OF THE SEEDED/COLUMNS SPLIT, and the half that is easy to get backwards.

    ``venue``, ``implementingAgency``, ``sponsor`` and ``workshopCode`` are SEEDED and must never
    ALSO be passed as columns: they belong to ``_STAGE_ONE_OWNS``, and ``seed_designer_prefill``
    applies ``promoted_values`` and updates those columns itself. Passing them here as well makes
    this door a second writer of a column with exactly one writer.
    """
    from app.api.routes import design_workshops as dw_routes

    header = set(dw_routes._HEADER_TEXT_COLUMNS) | set(dw_routes._HEADER_DATE_COLUMNS)
    stage_one = set(dw_routes._STAGE_ONE_OWNS)
    passed = set(COLUMN_KEYS)
    assert passed, "the columns map must be readable"
    assert passed <= header, f"not header columns: {sorted(passed - header)}"
    assert not (passed & stage_one), f"stage 1 owns these: {sorted(passed & stage_one)}"
    for key in ("venue", "implementingAgency", "sponsor", "workshopCode"):
        assert key in SEEDED_KEYS
        assert key not in passed


def test_the_promote_body_is_bounded_exactly_as_the_create_body_is():
    """TWO DOORS WRITING INTO ONE TABLE MUST NOT CARRY TWO RULES.

    Both bodies reach ``DesignWorkshopViewer`` through the same helper, and
    ``schemas/design_workshops.py`` writes out why the list is capped at all: the route reads every
    named account out of the user table before it writes anything, so an uncapped list lets one
    caller choose how much work the server does.
    """
    promote = AnnualPlanPromoteRequest.model_fields
    create = DesignWorkshopCreate.model_fields

    def _max_length(field) -> int | None:
        for meta in field.metadata:
            if hasattr(meta, "max_length"):
                return meta.max_length
        return None

    assert _max_length(promote["designerUserIds"]) == MAX_DESIGN_WORKSHOP_VIEWERS
    assert _max_length(promote["designerUserIds"]) == _max_length(create["designerUserIds"])
    assert _max_length(promote["designerUserId"]) == _max_length(create["designerUserId"]) == 64


def test_the_promote_body_bounds_the_template_id_to_the_same_closed_set_the_create_body_does():
    """**THE THIRD FIELD, AND IT WAS THE ONE NOBODY PINNED.**

    The test above pins the two designer fields and has done since the body landed; ``templateId``
    was never mentioned, and it reaches the same ``DesignWorkshop.templateId`` — plain ``TEXT``, no
    enum, no CHECK. ``resolve_template_id`` hands an unknown header value back verbatim and
    ``report_templates.template()`` falls back to the DCH standard for anything it does not know, so
    a typo was a 201 that stored a token nothing offers, printed the DCH standard on every report
    until stage 20 was answered, and wrote the bogus id into the ``DwReportExport`` provenance
    register where the history screen renders it as the NAME of the template a file used.

    ``PHOTO-CATALOGUE`` is the realistic mistake (the id is ``PHOTO_CATALOGUE``), and the assertion
    is that the two doors answer the SAME WAY rather than that either answers a particular way.
    """
    from pydantic import ValidationError

    for bad in ("PHOTO-CATALOGUE", "DCH_STANDRD", "", "anything at all"):
        with pytest.raises(ValidationError) as promote_refusal:
            AnnualPlanPromoteRequest(templateId=bad)
        with pytest.raises(ValidationError):
            DesignWorkshopCreate(title="A workshop", templateId=bad)
        assert "templateId must be one of" in str(promote_refusal.value)

    # AND THE DEFAULT IS STILL ACCEPTED, or the guard has closed the door it was protecting.
    assert AnnualPlanPromoteRequest().templateId == "DCH_STANDARD"
    assert AnnualPlanPromoteRequest(templateId="PHOTO_CATALOGUE").templateId == "PHOTO_CATALOGUE"


def test_the_promotion_folds_the_lead_designer_into_the_set_that_is_checked_and_granted():
    """**ONE CREATION PATH MEANS ONE NORMALISATION OF THE TWO DESIGNER FIELDS.**

    ``open_design_workshop`` validates and grants ``designer_ids`` and uses ``designer_id`` for
    nothing but the prefill. So a body naming only ``designerUserId``, passed through unfolded,
    produced: ``wanted = set() - {actor.id}`` empty, so ``assert_every_designer_may_be_named`` never
    ran — no empanelment roster read, no platform allow-list read, no ``DESIGN_WORKSHOP_ROLES``
    test; ``attach_the_named_designers`` skipped, so no viewer row and a 404 for the named person on
    the workshop that carries their name; and ``seed_designer_prefill`` copying their displayName,
    biography, designation, qualification, phone, email and postal address into stage 1 and stage 3
    of a report bound for a ministry anyway. ``POST /api/design-workshops`` 422s the identical body.

    Read at the source because the behavioural version needs Postgres, and because what is being
    asserted is that the normalisation happens AT ALL — ``named_designer_team(lead, [])`` returning
    ``(lead, [lead])`` is already pinned by ``tests/test_workshop_designer_naming.py``.
    """
    source = inspect.getsource(annual_plan.promote_entry)
    assert "named_designer_team(" in source, (
        "promote_entry hands the raw body to open_design_workshop; a lead named alone is then "
        "neither eligibility-checked nor granted, while their profile is copied into the report"
    )
    call = source.index("named_designer_team(")
    opened = source.index("open_design_workshop(")
    assert call < opened, "the normalisation has to happen BEFORE the workshop is created"
    assert "designer_id=lead_id" in source
    assert "designer_ids=granted_ids" in source


def test_the_promotion_stamp_is_a_compare_and_set_and_not_a_blind_update():
    """**THE DATABASE DOES NOT BACK THE ALREADY-PROMOTED REFUSAL, AND A COMMENT SAID IT DID.**

    ``AnnualPlanEntry.designWorkshopId`` is ``@unique``, which stops two PLAN ROWS pointing at one
    workshop — the thing ``test_the_database_also_refuses_a_second_workshop_on_one_row`` below
    actually exercises. It says nothing about one plan row being promoted twice: each promotion
    mints a fresh workshop id, so no other row holds either value and neither UPDATE conflicts. Two
    managers pressing "Open the workshop" on one row inside the second ``open_design_workshop``
    takes — or one browser retrying the POST — both read ``designWorkshopId = None``, both pass the
    409 above, and two complete workshops are created with the same title, the same seeded stage-1
    header and the same promoted ``workshopCode``. The last ``update`` wins; the other is a
    permanent orphan the directory can never reach, delete or explain.

    AST rather than a substring search, because what is being asserted is the SHAPE of the write:
    an ``update_many`` whose WHERE carries ``designWorkshopId: None`` beside the id.
    """
    tree = ast.parse(textwrap.dedent(inspect.getsource(annual_plan.promote_entry)))
    stamps = [
        node
        for node in ast.walk(tree)
        if isinstance(node, ast.Call)
        and isinstance(node.func, ast.Attribute)
        and node.func.attr == "update_many"
    ]
    assert len(stamps) == 1, "the promotion stamp is not a single update_many"
    where = next(kw.value for kw in stamps[0].keywords if kw.arg == "where")
    predicate = ast.unparse(where)
    assert "'designWorkshopId': None" in predicate, (
        f"the stamp's WHERE is {predicate}; without the None predicate it is an unconditional "
        "overwrite and a second promotion of the same row simply replaces the first"
    )

    # AND THE DOCSTRING SAYS THE UNIQUE INDEX DOES **NOT** BACK THIS REFUSAL. It claimed the
    # opposite — "`designWorkshopId` is `@unique`, so the database refuses it too" — which is true
    # of a different thing entirely (two PLAN ROWS pointing at one workshop) and is exactly the
    # sentence that would talk the next reviewer out of this finding. Asserted as a POSITIVE
    # correction rather than as the absence of the old wording, because the correction has to quote
    # what it is correcting for the next reader to follow it.
    source = inspect.getsource(annual_plan.promote_entry)
    assert "THE DATABASE DOES NOT BACK THIS REFUSAL" in source, (
        "promote_entry no longer records that the unique index does not cover a row promoted twice"
    )
    # AND THE LOSER'S WORKSHOP IS TAKEN BACK OUT. A byte-identical twin left in the directory is
    # indistinguishable from the winner to anybody who later has to decide which is real.
    assert "deletedAt" in source


def test_a_plan_row_with_no_title_still_produces_a_scannable_workshop_title():
    """``DesignWorkshop.title`` is NOT NULL and is the one promoted column ``_coerce_promoted``
    refuses to blank, so a plan row with no title still has to produce one — and never the bare
    number, because a list of three hundred rows reading "DPW/2026/017" is a list nobody can scan."""

    class _Entry:
        workshopNo = "DPW/2026/017"
        plannedTitle = None
        craftName = "Kachchh weaving"
        district = "Kachchh"
        state = "Gujarat"

    title = annual_plan.promotion_title(_Entry())
    assert title.startswith("DPW/2026/017")
    assert "Kachchh weaving" in title
    assert len(title) <= 220

    class _Bare:
        workshopNo = "DPW/2026/018"
        plannedTitle = None
        craftName = None
        district = None
        state = None

    assert annual_plan.promotion_title(_Bare()) == "DPW/2026/018"

    class _Long:
        workshopNo = "DPW/2026/019"
        plannedTitle = "क" * 400
        craftName = None
        district = None
        state = None

    assert len(annual_plan.promotion_title(_Long())) == 220


# --------------------------------------------------------------------------------------
# 2. The promotion itself. NEEDS POSTGRES.
# --------------------------------------------------------------------------------------


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


@pytest.fixture(scope="module")
async def world():
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]
    email = f"annual-plan-promoter-{stamp}@example.org"
    await db.connect()
    try:
        admin = await db.user.create(
            data={
                "email": email,
                "name": f"Ministry admin {stamp}",
                "role": "MINISTRY_ADMIN",
                "passwordHash": hash_password(PASSWORD),
            }
        )
        await db.accessroster.create(
            data={
                "email": email,
                "status": "ACTIVE",
                "admitRole": "MINISTRY_ADMIN",
                "joinedAt": datetime.now(UTC),
                "notes": "Seeded by tests/test_annual_plan_promotion.py.",
            }
        )
    finally:
        await db.disconnect()
    with TestClient(app) as client:
        yield {"client": client, "admin": admin, "stamp": stamp}


def _headers(world) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(subject=world['admin'].id)}"}


#: BOUNDARY-LENGTH VALUES, AND THAT IS THE POINT OF THE FIXTURE. The failure these tests exist to
#: catch appears ONLY at the boundary: a fixture of short strings passes with every cap wrong.
BOUNDARY = {
    "craftName": "c" * 160,
    "clusterName": "k" * 160,
    "state": "s" * 80,
    "district": "d" * 80,
    "venue": "v" * 220,
    "implementingAgency": "i" * 220,
    "sponsor": "p" * 220,
    "workshopNo": "w" * 60,
}


async def _entry(**overrides: Any):
    data = {
        "planYear": 2099,
        "workshopNo": overrides.pop("workshopNo", f"DPW/2099/{uuid.uuid4().hex[:6]}"),
        "plannedTitle": "A planned workshop",
        "workshopKind": "DESIGN_PROTOTYPE_DEVELOPMENT",
        "plannedStartDate": datetime(2026, 3, 12, tzinfo=UTC),
        "plannedEndDate": datetime(2026, 3, 26, tzinfo=UTC),
    }
    data.update(overrides)
    data["workshopNoKey"] = data["workshopNo"].strip().upper()
    return await db.annualplanentry.create(data=data)


@needs_db
async def test_promoting_a_row_creates_one_workshop_and_records_the_link(world) -> None:
    await db.connect()
    try:
        entry = await _entry()
    finally:
        await db.disconnect()

    response = world["client"].post(
        f"/api/annual-plan/{entry.id}/promote", json={}, headers=_headers(world)
    )
    assert response.status_code == 201, response.text
    body = response.json()
    assert body["entry"]["standing"] == "PROMOTED"
    assert body["entry"]["designWorkshopId"] == body["workshop"]["id"]
    assert body["entry"]["promotedAt"] is not None


@needs_db
async def test_a_promoted_row_cannot_be_promoted_twice(world) -> None:
    """409 NAMING THE EXISTING WORKSHOP. The unique index refuses it too, but a constraint violation
    cannot say WHICH workshop, and "which" is the only useful thing to tell the administrator."""
    await db.connect()
    try:
        entry = await _entry()
    finally:
        await db.disconnect()
    assert (
        world["client"]
        .post(f"/api/annual-plan/{entry.id}/promote", json={}, headers=_headers(world))
        .status_code
        == 201
    )
    second = world["client"].post(
        f"/api/annual-plan/{entry.id}/promote", json={}, headers=_headers(world)
    )
    assert second.status_code == 409
    assert entry.workshopNo in second.json()["detail"]


@needs_db
async def test_the_database_also_refuses_a_second_workshop_on_one_row(world) -> None:
    """THE ROUTE IS NOT THE ONLY GUARD, because a rule like this gets a second door added to it."""
    from prisma.errors import UniqueViolationError

    await db.connect()
    try:
        first = await _entry()
        second = await _entry()
        promoted = await db.designworkshop.create(
            data={
                "title": "A workshop",
                "templateId": "DCH_STANDARD",
                "createdById": world["admin"].id,
                "status": "DRAFT",
            }
        )
        await db.annualplanentry.update(
            where={"id": first.id}, data={"designWorkshopId": promoted.id}
        )
        with pytest.raises(UniqueViolationError):
            await db.annualplanentry.update(
                where={"id": second.id}, data={"designWorkshopId": promoted.id}
            )
    finally:
        await db.disconnect()


@needs_db
async def test_the_promoted_workshop_survives_its_first_stage_one_save(world) -> None:
    """THE DEFECT TEST, WITH A BOUNDARY-LENGTH FIXTURE.

    Promote, read the workshop, save stage 1 with an unrelated field, re-read: every promoted column
    unchanged. With short strings this passes with every cap wrong, which is why every text value in
    the fixture is exactly its FieldSpec's ``max_length``.
    """
    await db.connect()
    try:
        entry = await _entry(**BOUNDARY)
    finally:
        await db.disconnect()

    created = world["client"].post(
        f"/api/annual-plan/{entry.id}/promote", json={}, headers=_headers(world)
    )
    assert created.status_code == 201, created.text
    workshop_id = created.json()["workshop"]["id"]

    before = world["client"].get(
        f"/api/design-workshops/{workshop_id}", headers=_headers(world)
    ).json()
    for column in ("craftName", "clusterName", "state", "district", "venue"):
        assert before[column] == BOUNDARY[column], column

    saved = world["client"].put(
        f"/api/design-workshops/{workshop_id}/stages/WORKSHOP_SETUP",
        json={"entries": [{"entityKey": "workshopSetup", "data": {"block": "Bhujodi"}}]},
        headers=_headers(world),
    )
    assert saved.status_code in (200, 201), saved.text

    after = world["client"].get(
        f"/api/design-workshops/{workshop_id}", headers=_headers(world)
    ).json()
    for column in ("craftName", "clusterName", "state", "district", "venue", "workshopCode"):
        assert after.get(column) == before.get(column), f"{column} was nulled by the first save"


@needs_db
async def test_a_withdrawn_row_cannot_be_promoted(world) -> None:
    await db.connect()
    try:
        entry = await _entry(withdrawnAt=datetime.now(UTC))
    finally:
        await db.disconnect()
    response = world["client"].post(
        f"/api/annual-plan/{entry.id}/promote", json={}, headers=_headers(world)
    )
    assert response.status_code == 409
    assert "withdrawn" in response.json()["detail"].lower()


@needs_db
async def test_naming_an_ineligible_designer_refuses_the_whole_promotion(world) -> None:
    """THE ORPHAN-DRAFT FAILURE. Eligibility is asked ABOVE the create, so a refusal leaves no
    committed, untitled-looking workshop behind — and the plan row stays un-promoted, so the
    administrator can simply try again with the right name."""
    await db.connect()
    try:
        entry = await _entry()
        professor = await db.user.create(
            data={
                "email": f"plan-prof-{uuid.uuid4().hex[:8]}@example.org",
                "name": "A professor",
                "role": "PROFESSOR",
                "passwordHash": hash_password(PASSWORD),
            }
        )
        before = await db.designworkshop.count()
    finally:
        await db.disconnect()

    response = world["client"].post(
        f"/api/annual-plan/{entry.id}/promote",
        json={"designerUserIds": [professor.id]},
        headers=_headers(world),
    )
    assert response.status_code == 422
    assert professor.id in response.text or professor.email in response.text

    await db.connect()
    try:
        assert await db.designworkshop.count() == before
        reread = await db.annualplanentry.find_unique(where={"id": entry.id})
        assert reread.designWorkshopId is None
    finally:
        await db.disconnect()


@needs_db
async def test_naming_an_ineligible_lead_designer_alone_refuses_the_promotion_too(world) -> None:
    """**THE SINGULAR TWIN OF THE TEST ABOVE, AND THE ONE THAT HAD NEVER BEEN WRITTEN.**

    Every promote test in this file sent the PLURAL field, so the singular path was never exercised
    — and it was the one with the hole in it. ``open_design_workshop`` builds ``wanted`` from
    ``designer_ids`` alone, so ``{"designerUserId": <a professor>}`` used to skip
    ``assert_every_designer_may_be_named`` entirely: 201, no viewer row for the person named (a 404
    for them on the workshop that carries their name), and ``seed_designer_prefill`` copying their
    profile — displayName, biography, designation, qualification, phone, email, postal address —
    into stage 1 and stage 3 of a ministry report. The identical body on ``POST
    /api/design-workshops`` was a 422 before anything was created.

    UNVERIFIED LOCALLY: the generated Prisma client on this machine predates today's models and
    ``prisma generate`` does not run here, so this first executes in CI.
    """
    await db.connect()
    try:
        entry = await _entry()
        professor = await db.user.create(
            data={
                "email": f"plan-lead-prof-{uuid.uuid4().hex[:8]}@example.org",
                "name": "A professor",
                "role": "PROFESSOR",
                "passwordHash": hash_password(PASSWORD),
            }
        )
        before = await db.designworkshop.count()
    finally:
        await db.disconnect()

    response = world["client"].post(
        f"/api/annual-plan/{entry.id}/promote",
        json={"designerUserId": professor.id},
        headers=_headers(world),
    )
    assert response.status_code == 422, response.text
    assert professor.id in response.text or professor.email in response.text

    await db.connect()
    try:
        assert await db.designworkshop.count() == before, (
            "a workshop was created for a designer the eligibility rule refuses — and its stage 1 "
            "and stage 3 now hold that account's profile"
        )
        reread = await db.annualplanentry.find_unique(where={"id": entry.id})
        assert reread.designWorkshopId is None
    finally:
        await db.disconnect()


@needs_db
async def test_a_lead_designer_named_alone_is_granted_the_workshop_they_are_named_on(world) -> None:
    """The other half of the same defect: the lead has to END UP with a viewer row.

    ``attach_the_named_designers`` ran only ``if designer_ids``, so a body naming only the lead
    wrote no row at all and the designer whose name is on the cover got a 404 on their own
    workshop — "the exact silent failure ``attach_the_named_designers`` documents itself as existing
    to prevent".

    UNVERIFIED LOCALLY — see the test above.
    """
    await db.connect()
    try:
        entry = await _entry()
        designer = await db.user.create(
            data={
                "email": f"plan-lead-{uuid.uuid4().hex[:8]}@example.org",
                "name": "Meera Kanungo",
                "role": "DESIGNER",
                "passwordHash": hash_password(PASSWORD),
            }
        )
    finally:
        await db.disconnect()

    created = world["client"].post(
        f"/api/annual-plan/{entry.id}/promote",
        json={"designerUserId": designer.id},
        headers=_headers(world),
    )
    assert created.status_code == 201, created.text
    workshop_id = created.json()["workshop"]["id"]

    await db.connect()
    try:
        granted = await db.designworkshopviewer.find_many(
            where={"designWorkshopId": workshop_id}
        )
        assert [row.userId for row in granted] == [designer.id], (
            "the lead named alone was given no viewer row, so the designer this workshop is FOR "
            "answers 404 on it while stage 1 carries their name"
        )
    finally:
        await db.disconnect()


@needs_db
async def test_a_second_promotion_that_slips_past_the_check_creates_no_orphan_workshop(
    world,
) -> None:
    """**THE RACE, FORCED RATHER THAN RUN.**

    The window is between ``promote_entry``'s ``if entry.designWorkshopId`` check and its stamp, and
    it is real (`open_design_workshop` is four awaits and explicitly not transactional). It cannot
    be produced reliably from a test client, so the test does what the race does: hands
    ``promote_entry`` a STALE row — the snapshot it read before somebody else promoted — and asserts
    the compare-and-set catches what the in-memory check cannot.

    TWO PROPERTIES, AND THE SECOND IS THE ONE THAT COSTS MONEY. The call is refused (409 naming the
    workshop the row actually points at), AND the workshop this call had already created is taken
    back out — a byte-identical twin left in the directory, with the same title, the same seeded
    stage-1 header and the same promoted ``workshopCode``, is indistinguishable from the winner to
    anybody who later has to decide which one is real.

    UNVERIFIED LOCALLY — see above.
    """
    from fastapi import HTTPException

    await db.connect()
    try:
        entry = await _entry()
        stale = await db.annualplanentry.find_unique(where={"id": entry.id})
        assert stale.designWorkshopId is None
    finally:
        await db.disconnect()

    winner = world["client"].post(
        f"/api/annual-plan/{entry.id}/promote", json={}, headers=_headers(world)
    )
    assert winner.status_code == 201, winner.text
    winning_id = winner.json()["workshop"]["id"]

    await db.connect()
    try:
        before = await db.designworkshop.count(where={"deletedAt": None})
        with pytest.raises(HTTPException) as refused:
            await annual_plan.promote_entry(
                stale, actor=world["admin"], designer_id=None, designer_ids=[]
            )
        assert refused.value.status_code == 409

        reread = await db.annualplanentry.find_unique(where={"id": entry.id})
        assert reread.designWorkshopId == winning_id, "the loser overwrote the winner's link"
        assert await db.designworkshop.count(where={"deletedAt": None}) == before, (
            "the losing promotion left a live orphan workshop in the directory that the plan row "
            "can never reach, delete or explain"
        )
    finally:
        await db.disconnect()


@needs_db
async def test_withdrawing_a_promoted_row_is_refused(world) -> None:
    """A designer may be standing in the courtyard. Withdrawing the line would take it out of the
    directory while the workshop, its viewers, its media and its report went on existing — and
    nothing anywhere would say why the two disagree."""
    await db.connect()
    try:
        entry = await _entry()
    finally:
        await db.disconnect()
    assert (
        world["client"]
        .post(f"/api/annual-plan/{entry.id}/promote", json={}, headers=_headers(world))
        .status_code
        == 201
    )
    refused = world["client"].post(
        f"/api/annual-plan/{entry.id}/withdraw", json={}, headers=_headers(world)
    )
    assert refused.status_code == 409
    assert "cancel the workshop" in refused.json()["detail"].lower()
