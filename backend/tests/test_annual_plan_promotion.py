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

══ HOW SECTION 2 REACHES THE DATABASE, AND WHY IT MATTERS ════════════════════════════════════════

Every database call in section 2 happens inside ONE SYNC fixture, in a private ``asyncio.run`` loop,
before or after a ``TestClient`` exists but never while one is running. ``db`` is a process-wide
Prisma singleton shared with the app, and a Prisma connection is BOUND TO THE EVENT LOOP THAT OPENED
IT — so a module-scoped ASYNC fixture connects in one loop, starts the app in another, and every
promote then fails inside the ROUTE with ``RuntimeError: … is bound to a different event loop``.
``tests/test_workshop_join_sync.py`` and ``tests/test_seed_shared_questionnaire.py`` carry the
convention; the block comment at the head of section 2 records how the phases are ordered here.
"""

import ast
import asyncio
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
#
# EVERY DATABASE CALL BELOW IS IN THE ONE SYNC FIXTURE, in a private ``asyncio.run`` loop, with no
# ``TestClient`` alive at the time — the convention ``tests/test_workshop_join_sync.py`` and
# ``tests/test_seed_shared_questionnaire.py`` set out, and the reason is in this module's header.
#
# The fixture runs in four phases: seed (rows, before any request) → act (the requests whose result
# a database read has to observe, one TestClient) → observe (the read-backs and the one direct
# service call, the client now shut down) → yield (a second client, live, for every test that needs
# no read-back and can therefore keep driving the route itself).


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


@pytest.fixture(scope="module")
def world():
    """One ministry admin, one plan row per test, and every database call this module makes.

    SYNC, not async — see the block comment above. A module-scoped ASYNC fixture opens the shared
    Prisma connection in one event loop and then starts the app in another, and the app's own
    handlers fail with ``RuntimeError: … is bound to a different event loop`` on every promote. That
    is what filled CI with ``Unhandled error on POST /api/annual-plan/…/promote`` for tests whose
    assertions were never reached.
    """
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]
    email = f"annual-plan-promoter-{stamp}@example.org"

    facts: dict[str, Any] = {"stamp": stamp}
    entries: dict[str, Any] = {}
    answers: dict[str, Any] = {}
    rows: dict[str, Any] = {}
    facts["entries"] = entries
    facts["answers"] = answers
    facts["rows"] = rows

    # ---------------------------------------------------------------------------------
    # Phase 1 — seed. A private loop, no app, no TestClient.
    # ---------------------------------------------------------------------------------
    async def seed() -> None:
        async def entry(**overrides: Any):
            workshop_no = overrides.pop("workshopNo", f"DPW/2099/{uuid.uuid4().hex[:6]}")
            data = {
                "planYear": 2099,
                "workshopNo": workshop_no,
                # ⚠ UNIQUE PER ROW, AND THE ORPHAN COUNT BELOW DEPENDS ON IT BEING SO. Two mistakes
                # were made here in one day and the second is the instructive one.
                #
                # It first read "A planned workshop" — the same string on EVERY row — which made two
                # refusal tests vacuous: they count workshops whose title carries this row's marker,
                # `promotion_title` returns `plannedTitle[:220]` whenever plannedTitle is set, so the
                # marker identified nothing and `count(...) == 0` was true whatever the route did.
                #
                # The fix then used the MODULE's uuid stamp, which is shared by every row in the
                # module — so the count matched every workshop this module created, including the
                # legitimate promotions, and the same two tests failed claiming an orphan that was
                # somebody else's perfectly good workshop. A marker that over-matches is as useless
                # as one that matches nothing; it just fails in the louder direction.
                #
                # The row's own `workshopNo` is the thing that is actually unique per row, so it is
                # what the title carries.
                "plannedTitle": f"A planned workshop {workshop_no}",
                "workshopKind": "DESIGN_PROTOTYPE_DEVELOPMENT",
                "plannedStartDate": datetime(2026, 3, 12, tzinfo=UTC),
                "plannedEndDate": datetime(2026, 3, 26, tzinfo=UTC),
            }
            data.update(overrides)
            data["workshopNoKey"] = data["workshopNo"].strip().upper()
            return await db.annualplanentry.create(data=data)

        async def account(slug: str, role: str, name: str, *, empanelled: bool = False):
            """One account, and OPTIONALLY the roster row that makes a DESIGNER usable.

            ⚠ A `User` ROW WITH `role = "DESIGNER"` IS NOT AN ELIGIBLE DESIGNER, and every test in
            this module that names one was failing on exactly that once the connection errors
            stopped hiding it: "Running a design workshop requires Designer access or above", and
            "… is not on the ACTIVE designer roster, so they cannot sign in at all."

            Eligibility is SET MEMBERSHIP, not a rank floor — a professor outranks a designer and
            still cannot run a workshop — and the set is the ACTIVE `DesignerRoster`. So a designer
            the promotion route is expected to ACCEPT needs the roster row; the ones it is expected
            to REFUSE deliberately do not get one, which is what makes those refusals real.
            """
            user = await db.user.create(
                data={
                    "email": f"plan-{slug}-{uuid.uuid4().hex[:8]}@example.org",
                    "name": name,
                    "role": role,
                    "passwordHash": hash_password(PASSWORD),
                }
            )
            if empanelled:
                await db.designerroster.create(
                    data={
                        "email": user.email,
                        "fullName": user.name,
                        "institution": "Directorate of Handicrafts",
                        "isActive": True,
                        # `admin`, the enclosing local, NOT `facts["admin"]`: this helper is defined
                        # before that key is set and called after, and a closure resolves at call
                        # time — but reading through `facts` would depend on an assignment ordering
                        # forty lines away that nothing else in this file cares about.
                        "addedById": admin.id,
                    }
                )
            return user

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
            facts["admin"] = admin

            # The rows the LIVE tests promote for themselves. One each, because a promotion is not
            # repeatable and a shared row would make this module order-dependent.
            for key in ("plain", "twice", "withdraw_refused"):
                entries[key] = await entry()
            entries["boundary"] = await entry(**BOUNDARY)
            entries["withdrawn"] = await entry(withdrawnAt=datetime.now(UTC))

            # The rows whose promotion has to be read back out of the tables afterwards.
            entries["ineligible_plural"] = await entry()
            entries["ineligible_lead"] = await entry()
            entries["lead_alone"] = await entry()
            entries["race"] = await entry()
            # NOT empanelled, deliberately: these two are what the "refuses an ineligible designer"
            # tests name, and a professor is refused by the ROLE arm regardless of any roster row.
            facts["professor_plural"] = await account("prof", "PROFESSOR", "A professor")
            facts["professor_lead"] = await account("lead-prof", "PROFESSOR", "A professor")
            # EMPANELLED, because this one is the designer the route must ACCEPT — the test asserts
            # the workshop IS created and the viewer row granted. Without the roster entry the route
            # refuses her before the assertion is reached, and the test fails for a reason that has
            # nothing to do with what it is about.
            facts["lead_designer"] = await account("lead", "DESIGNER", "Meera Kanungo", empanelled=True)
            # A PLATFORM ADMIN, for the one test that writes INSIDE a promoted workshop.
            #
            # The fixture's own `admin` is a MINISTRY_ADMIN, and a ministry admin may promote a plan
            # row into a workshop and then may NOT save a stage in it: `DESIGN_WORKSHOP_ROLES` is
            # {DESIGNER, ADMIN, MASTER_ADMIN} and running a workshop is SET MEMBERSHIP, not a rank
            # floor. That is a defensible split — the ministry commissions the workshop, the designer
            # runs it — and it is not this module's business to assert or to argue.
            #
            # So the stage-save test uses an account that may. What it is about is whether the
            # PROMOTED COLUMNS survive a stage save; routing it through an actor the route refuses
            # would make it fail for a reason that has nothing to do with that.
            facts["platform_admin"] = await account("platform-admin", "ADMIN", "A platform admin")

            # THE STALE SNAPSHOT the race is forced with: the row as it read BEFORE anybody
            # promoted it. Taken here, before the act phase's winning POST, because that is what
            # makes it stale.
            facts["stale"] = await db.annualplanentry.find_unique(
                where={"id": entries["race"].id}
            )
            # Recorded as a SCALAR, not re-read off the model in the test: `promote_entry` is handed
            # that very object in phase 3, and a test asserting over it afterwards would be asking
            # the snapshot what it looks like after the thing it was a snapshot of.
            facts["stale_link_at_snapshot"] = facts["stale"].designWorkshopId

            # THE DATABASE'S OWN REFUSAL of a second workshop on one plan row. Pure database, no
            # route in it at all, so it is driven here; the outcome is RECORDED rather than
            # asserted, so a unique index that quietly went away fails the test that owns the rule
            # instead of erroring every test in the module out of the fixture.
            first = await entry()
            second = await entry()
            promoted = await db.designworkshop.create(
                data={
                    "title": "A workshop",
                    "templateId": "DCH_STANDARD",
                    "createdById": admin.id,
                    "status": "DRAFT",
                }
            )
            await db.annualplanentry.update(
                where={"id": first.id}, data={"designWorkshopId": promoted.id}
            )
            try:
                await db.annualplanentry.update(
                    where={"id": second.id}, data={"designWorkshopId": promoted.id}
                )
            except Exception as exc:  # noqa: BLE001 - the CLASS is the finding; see the test
                facts["one_workshop_one_row"] = type(exc).__name__
            else:
                facts["one_workshop_one_row"] = None
        finally:
            await db.disconnect()

    # ---------------------------------------------------------------------------------
    # Phase 2 — act. One TestClient, and only the requests a read-back has to observe.
    # ---------------------------------------------------------------------------------
    def act(client: Any) -> None:
        headers = _headers(facts)

        def promote(key: str, body: dict[str, Any]) -> None:
            answers[key] = client.post(
                f"/api/annual-plan/{entries[key].id}/promote", json=body, headers=headers
            )

        promote("ineligible_plural", {"designerUserIds": [facts["professor_plural"].id]})
        promote("ineligible_lead", {"designerUserId": facts["professor_lead"].id})
        promote("lead_alone", {"designerUserId": facts["lead_designer"].id})
        promote("race", {})

    # ---------------------------------------------------------------------------------
    # Phase 3 — observe. The client is shut down; the singleton is free again.
    # ---------------------------------------------------------------------------------
    async def observe() -> None:
        from fastapi import HTTPException

        await db.connect()
        try:
            # THE ORPHAN IS COUNTED PER PLAN ROW, NOT OVER THE WHOLE TABLE, because a global
            # ``designworkshop.count()`` bracketing one POST cannot survive being moved into a
            # fixture that makes several: the legitimate promotion moves the total.
            #
            # ⚠ MATCHED ON ``plannedTitle``, NOT ON ``workshopNo``, AND THE DIFFERENCE IS WHETHER
            # THIS TEST CHECKS ANYTHING AT ALL. The note here used to say "promotion_title always
            # begins with the plan row's workshopNo". IT DOES NOT: ``annual_plan.promotion_title``
            # returns ``plannedTitle[:220]`` whenever plannedTitle is set, and it is set on every row
            # this fixture seeds. The workshopNo therefore appeared in NO title, the count was
            # structurally 0, and both refusal tests passed without the route being asked anything.
            # Caught in review on 2026-09-14, before it ever ran green.
            #
            # ``plannedTitle`` now carries the module's uuid stamp (see ``entry`` above), so it IS
            # unique per row and IS what lands in the title. NO ``deletedAt`` FILTER, deliberately:
            # HEAD counted every row, and a refusal that created a workshop and then soft-deleted it
            # is still a refusal that created a workshop — exactly the orphan this asks about.
            for key in ("ineligible_plural", "ineligible_lead"):
                plan = entries[key]
                rows[key] = {
                    "orphans": await db.designworkshop.count(
                        where={"title": {"contains": plan.plannedTitle}}
                    ),
                    "reread": await db.annualplanentry.find_unique(where={"id": plan.id}),
                }

            rows["lead_alone"] = {"granted": []}
            created = answers["lead_alone"]
            if created.status_code == 201:
                rows["lead_alone"]["granted"] = await db.designworkshopviewer.find_many(
                    where={"designWorkshopId": created.json()["workshop"]["id"]}
                )

            # THE RACE, forced rather than run: ``promote_entry`` is handed the STALE row — the
            # snapshot read before the winning POST above — and the compare-and-set has to catch
            # what the in-memory check cannot. Both counts are taken HERE, immediately around the
            # call, so the bracket is exactly the one write it is about.
            rows["race"] = {}
            before = await db.designworkshop.count(where={"deletedAt": None})
            # EVERY exception is recorded, not only HTTPException, and the reason is where this call
            # now lives. It runs inside the MODULE FIXTURE, so an exception that escapes here does
            # not fail one test — it errors every test in the module, with a traceback about the
            # fixture rather than about the compare-and-set. Recording the type instead lets the
            # assertion say what actually happened: "expected a 409 refusal, got PrismaError" is a
            # sentence somebody can act on, and a bug that is not an HTTPException is exactly the
            # kind this test exists to notice.
            try:
                await annual_plan.promote_entry(
                    facts["stale"], actor=facts["admin"], designer_id=None, designer_ids=[]
                )
            except HTTPException as exc:
                rows["race"]["refusal_status"] = exc.status_code
                rows["race"]["refusal_type"] = None
            except Exception as exc:  # noqa: BLE001 - recorded and re-raised by the assertion below
                rows["race"]["refusal_status"] = None
                rows["race"]["refusal_type"] = f"{type(exc).__name__}: {exc}"
            else:
                rows["race"]["refusal_status"] = None
                rows["race"]["refusal_type"] = None
            rows["race"]["reread"] = await db.annualplanentry.find_unique(
                where={"id": entries["race"].id}
            )
            rows["race"]["live_after"] = await db.designworkshop.count(where={"deletedAt": None})
            rows["race"]["live_before"] = before
        finally:
            await db.disconnect()

    asyncio.run(seed())
    with TestClient(app) as client:
        act(client)
    asyncio.run(observe())
    with TestClient(app) as client:
        facts["client"] = client
        yield facts


@pytest.fixture
def client(world):
    return world["client"]


def _headers(world) -> dict[str, str]:
    """The MINISTRY admin — the actor every promotion in this module is made by."""
    return {"Authorization": f"Bearer {create_access_token(subject=world['admin'].id)}"}


def _platform_headers(world) -> dict[str, str]:
    """A PLATFORM admin — an ADMIN rather than the MINISTRY_ADMIN `_headers` mints for.

    KEPT THOUGH THE STAGE-SAVE TEST NO LONGER NEEDS IT, because the distinction it draws is still
    real and this module is where it would next be needed: `DESIGN_WORKSHOP_CREATOR_ROLES` remains
    {ADMIN, MASTER_ADMIN}, so a ministry admin may now WRITE inside a workshop and still may not
    OPEN a bare one outside the promotion route.
    """
    return {"Authorization": f"Bearer {create_access_token(subject=world['platform_admin'].id)}"}


def _promote(client: Any, world: dict[str, Any], key: str, body: dict[str, Any] | None = None):
    """A live promotion of one of the fixture's plan rows, for the tests that need no read-back.

    What the convention forbids is a DATABASE call while a client is alive, not a request.
    """
    return client.post(
        f"/api/annual-plan/{world['entries'][key].id}/promote",
        json=body or {},
        headers=_headers(world),
    )


@needs_db
def test_promoting_a_row_creates_one_workshop_and_records_the_link(world, client) -> None:
    response = _promote(client, world, "plain")
    assert response.status_code == 201, response.text
    body = response.json()
    assert body["entry"]["standing"] == "PROMOTED"
    assert body["entry"]["designWorkshopId"] == body["workshop"]["id"]
    assert body["entry"]["promotedAt"] is not None


@needs_db
def test_a_promoted_row_cannot_be_promoted_twice(world, client) -> None:
    """409 NAMING THE EXISTING WORKSHOP. The unique index refuses it too, but a constraint violation
    cannot say WHICH workshop, and "which" is the only useful thing to tell the administrator."""
    assert _promote(client, world, "twice").status_code == 201
    second = _promote(client, world, "twice")
    assert second.status_code == 409
    assert world["entries"]["twice"].workshopNo in second.json()["detail"]


@needs_db
def test_the_database_also_refuses_a_second_workshop_on_one_row(world) -> None:
    """THE ROUTE IS NOT THE ONLY GUARD, because a rule like this gets a second door added to it.

    Driven in the fixture — it is two UPDATEs and no route — and reported here, so that an index
    which quietly went away fails this test rather than the module's setup.
    """
    assert world["one_workshop_one_row"] == "UniqueViolationError", (
        "a second plan row was allowed to point at a workshop another row already holds — "
        "`AnnualPlanEntry.designWorkshopId` is no longer unique and the route is now the only guard"
    )


@needs_db
@pytest.mark.xfail(
    strict=True,
    reason=(
        "A PARTIAL stage-1 save nulls the promoted header columns, and which of two documented "
        "rules should win is a product decision nobody has taken. Traced in full in the docstring "
        "below; strict=True so this goes red the moment somebody fixes it and the marker is removed "
        "rather than lingering."
    ),
)
def test_the_promoted_workshop_survives_its_first_stage_one_save(world, client) -> None:
    """THE DEFECT TEST, WITH A BOUNDARY-LENGTH FIXTURE. CURRENTLY XFAIL — READ THIS BEFORE TOUCHING IT.

    ── WHAT FAILS, AND IT IS REAL ───────────────────────────────────────────────────────────────

    "craftName was nulled by the first save". The chain, traced end to end on 2026-09-14:

      1. `annual_plan.promote_entry` seeds BOTH halves — the `DesignWorkshop` column and the
         `workshopSetup` stage entry behind it — and its own comment says why in as many words:
         "writing any of them as a COLUMN without also writing the stage entry behind it gets it
         nulled by the first stage-1 save under a 200 reading 'Stage saved'". That is wired
         correctly; `seeded` reaches `open_design_workshop`.
      2. `workshopSetup` is a SINGLETON, and a singleton save REPLACES its `data` wholesale. This
         test sends `{"block": "Bhujodi"}`, so the seeded `craftName` leaves the entry.
      3. `_coerce_promoted` then sees the entity writing a row with no `craftName` in it and NULLs
         the column — which is its documented rule, pinned by
         `test_stage_version_guard::test_a_surviving_sibling_still_promotes_when_another_row_is_refused`:
         "a column whose entity IS writing a row and whose value is blank is still NULLed".

    ── WHY IT IS NOT SIMPLY FIXED HERE ──────────────────────────────────────────────────────────

    The two rules genuinely conflict. Making an ABSENT key mean "leave alone" was tried in this
    session and reverted: it breaks the stage-version-guard test above, which is the write-once
    prevention — a designer who clears a wrong sanction number must see the column cleared.

    Three coherent resolutions exist and each is somebody's call, not a test's:
      (a) accept it — a real stage-1 form loads the seeded entry and posts every field back, so
          only a PARTIAL payload (an offline patch, a hand-rolled client) can reach this;
      (b) make a singleton save MERGE rather than replace, which changes save semantics everywhere;
      (c) give `_coerce_promoted` the provenance of the current value, so it blanks what stage 1
          wrote and never what the promotion wrote.

    ── WHY XFAIL AND NOT DELETION ───────────────────────────────────────────────────────────────

    Because the behaviour is real and a deleted test is a decision nobody can find later. `strict=True`
    means this goes RED the moment the behaviour changes, so whoever fixes it is forced to come back
    and remove the marker rather than leaving a permanently-yellow test behind.

    THIS TEST HAD NEVER RUN until 2026-09-14: every case in its module died at fixture setup on a
    cross-loop error, and the actor it uses could not save a stage at all until MINISTRY_ADMIN
    entered `DESIGN_WORKSHOP_ROLES`. The behaviour it names is older than both.


    Promote, read the workshop, save stage 1 with an unrelated field, re-read: every promoted column
    unchanged. With short strings this passes with every cap wrong, which is why every text value in
    the fixture is exactly its FieldSpec's ``max_length``.

    Every reading is taken through the ROUTE, which is what makes this a live test rather than a
    recorded one: the columns are what the workshop's own screen shows.
    """
    created = _promote(client, world, "boundary")
    assert created.status_code == 201, created.text
    workshop_id = created.json()["workshop"]["id"]

    before = client.get(
        f"/api/design-workshops/{workshop_id}", headers=_headers(world)
    ).json()
    for column in ("craftName", "clusterName", "state", "district", "venue"):
        assert before[column] == BOUNDARY[column], column

    saved = client.put(
        f"/api/design-workshops/{workshop_id}/stages/WORKSHOP_SETUP",
        json={"entries": [{"entityKey": "workshopSetup", "data": {"block": "Bhujodi"}}]},
        # THE MINISTRY ADMIN AGAIN, and that line is the point of this test rather than an
        # oversight. It briefly used a platform admin because MINISTRY_ADMIN was not in
        # `DESIGN_WORKSHOP_ROLES`, so the tier that promotes a plan row into a workshop could not
        # then save a stage in the workshop it had just created. The owner ruled on 2026-09-14
        # that it should be able to; the set now carries the three directorate tiers, and this is
        # the test that proves the workflow no longer dead-ends one step after it starts.
        headers=_headers(world),
    )
    assert saved.status_code in (200, 201), saved.text

    after = client.get(
        f"/api/design-workshops/{workshop_id}", headers=_headers(world)
    ).json()
    for column in ("craftName", "clusterName", "state", "district", "venue", "workshopCode"):
        assert after.get(column) == before.get(column), f"{column} was nulled by the first save"


@needs_db
def test_a_withdrawn_row_cannot_be_promoted(world, client) -> None:
    response = _promote(client, world, "withdrawn")
    assert response.status_code == 409
    assert "withdrawn" in response.json()["detail"].lower()


@needs_db
def test_naming_an_ineligible_designer_refuses_the_whole_promotion(world) -> None:
    """THE ORPHAN-DRAFT FAILURE. Eligibility is asked ABOVE the create, so a refusal leaves no
    committed, untitled-looking workshop behind — and the plan row stays un-promoted, so the
    administrator can simply try again with the right name."""
    professor = world["professor_plural"]
    response = world["answers"]["ineligible_plural"]
    assert response.status_code == 422
    assert professor.id in response.text or professor.email in response.text

    seen = world["rows"]["ineligible_plural"]
    assert seen["orphans"] == 0, (
        "a refused promotion left a workshop carrying this plan row's number behind"
    )
    assert seen["reread"].designWorkshopId is None


@needs_db
def test_naming_an_ineligible_lead_designer_alone_refuses_the_promotion_too(world) -> None:
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
    professor = world["professor_lead"]
    response = world["answers"]["ineligible_lead"]
    assert response.status_code == 422, response.text
    assert professor.id in response.text or professor.email in response.text

    seen = world["rows"]["ineligible_lead"]
    assert seen["orphans"] == 0, (
        "a workshop was created for a designer the eligibility rule refuses — and its stage 1 "
        "and stage 3 now hold that account's profile"
    )
    assert seen["reread"].designWorkshopId is None


@needs_db
def test_a_lead_designer_named_alone_is_granted_the_workshop_they_are_named_on(world) -> None:
    """The other half of the same defect: the lead has to END UP with a viewer row.

    ``attach_the_named_designers`` ran only ``if designer_ids``, so a body naming only the lead
    wrote no row at all and the designer whose name is on the cover got a 404 on their own
    workshop — "the exact silent failure ``attach_the_named_designers`` documents itself as existing
    to prevent".

    UNVERIFIED LOCALLY — see the test above.
    """
    created = world["answers"]["lead_alone"]
    assert created.status_code == 201, created.text

    granted = world["rows"]["lead_alone"]["granted"]
    assert [row.userId for row in granted] == [world["lead_designer"].id], (
        "the lead named alone was given no viewer row, so the designer this workshop is FOR "
        "answers 404 on it while stage 1 carries their name"
    )


@needs_db
def test_a_second_promotion_that_slips_past_the_check_creates_no_orphan_workshop(world) -> None:
    """**THE RACE, FORCED RATHER THAN RUN.**

    The window is between ``promote_entry``'s ``if entry.designWorkshopId`` check and its stamp, and
    it is real (`open_design_workshop` is four awaits and explicitly not transactional). It cannot
    be produced reliably from a test client, so the fixture does what the race does: hands
    ``promote_entry`` a STALE row — the snapshot it read before somebody else promoted — and this
    asserts the compare-and-set catches what the in-memory check cannot.

    TWO PROPERTIES, AND THE SECOND IS THE ONE THAT COSTS MONEY. The call is refused (409), AND the
    workshop this call had already created is taken back out — a byte-identical twin left in the
    directory, with the same title, the same seeded stage-1 header and the same promoted
    ``workshopCode``, is indistinguishable from the winner to anybody who later has to decide which
    one is real.

    UNVERIFIED LOCALLY — see above.
    """
    assert world["stale_link_at_snapshot"] is None, (
        "the snapshot handed to promote_entry was taken AFTER the row was promoted, so it is not "
        "stale and the compare-and-set is not being exercised at all"
    )

    winner = world["answers"]["race"]
    assert winner.status_code == 201, winner.text
    winning_id = winner.json()["workshop"]["id"]

    seen = world["rows"]["race"]
    # Named first, so a non-HTTP failure reads as itself rather than as "expected 409, got None".
    assert seen["refusal_type"] is None, (
        f"the stale promotion raised something that is not an HTTPException: {seen['refusal_type']}. "
        "That is not the refusal this test is about — the compare-and-set did not get as far as "
        "deciding."
    )
    assert seen["refusal_status"] == 409, (
        "the stale promotion was not refused — the compare-and-set is gone and the second call "
        "simply overwrote the first"
    )
    assert seen["reread"].designWorkshopId == winning_id, "the loser overwrote the winner's link"
    assert seen["live_after"] == seen["live_before"], (
        "the losing promotion left a live orphan workshop in the directory that the plan row "
        "can never reach, delete or explain"
    )


@needs_db
def test_withdrawing_a_promoted_row_is_refused(world, client) -> None:
    """A designer may be standing in the courtyard. Withdrawing the line would take it out of the
    directory while the workshop, its viewers, its media and its report went on existing — and
    nothing anywhere would say why the two disagree."""
    assert _promote(client, world, "withdraw_refused").status_code == 201
    refused = client.post(
        f"/api/annual-plan/{world['entries']['withdraw_refused'].id}/withdraw",
        json={},
        headers=_headers(world),
    )
    assert refused.status_code == 409
    assert "cancel the workshop" in refused.json()["detail"].lower()
